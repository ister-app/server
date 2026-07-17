# Eventsysteem

Alles wat significant is, loopt asynchroon via RabbitMQ. Een trigger (API-call, scheduler, scan)
roept `MessageSender.send()` aan, die het event naar zijn queue routeert; een
`Handle<T>`-implementatie in `disk/`, `worker/`, `search/` of `transcoder/` consumeert het, doet
zijn werk en kan verdere events versturen. Het [volledige eventoverzicht](../diagrams/event-overview.md)
laat zien hoe de belangrijkste triggers uitwaaieren over de handlers.

## Het `Handle<T>`-contract

`Handle<T extends MessageData>` (core-module) is de centrale interface. `handles()` geeft het
`EventType` terug dat de handler bezit; de standaard-`listener()` weigert elk bericht waarvan het
`eventType`-veld niet overeenkomt voordat er naar `handle()` gedispatcht wordt. Handlers zijn
gewone Spring-beans met een `@RabbitListener` op hun queue.

**Geen Hibernate-sessie op listener-threads.** RabbitMQ-listener-threads hebben geen open sessie,
dus lazy navigeren over associaties gooit een `LazyInitializationException`. Handlers moeten wat ze
nodig hebben expliciet laden met repository-queries (fetch joins of speciale finder-methodes),
nooit door entity-grafen af te lopen.

## Twee enums, haal ze niet door elkaar

- **`EventType`** (`database/.../enums/EventType.java`) is het logische berichttype — de bron van
  waarheid voor welke soorten events er bestaan (31 waarden). `Handle.handles()` geeft er één terug.
- **`MessageQueue`** (`core/.../MessageQueue.java`) bevat de **basisnamen** van de queues.
  `MessageSender` mapt een event naar zijn queue.

Queue-namen volgen `app.ister.server.<event>[.<scope>]`, waarbij de scope een directorynaam of
nodenaam is, of ontbreekt bij globale queues. Die scoping is wat werk routeert naar de node die de
bestanden bezit: elke node declareert en beluistert alleen de queues van zijn eigen directories.

## Retries en dead-lettering

Mislukte listeners proberen het opnieuw met exponentiële backoff
(`spring.rabbitmq.listener.simple.retry.*` in `core.properties`: 3 pogingen, 2s beginInterval,
multiplier 2). Na de laatste mislukking verplaatst een `RepublishMessageRecoverer` het bericht naar
de **`app.ister.server.dead-letter`**-queue, met de exceptie bewaard in de message-headers
(`RabbitReliabilityConfig`). Recente mislukkingen voeden ook de `RecentFailuresBuffer` voor de
status-subscriptions ([hoofdstuk 5](05-continue-watching-and-status.md)). De e2e van de Helm-chart
faalt op elk dead-lettered event — daarom moet elke externe call achter een configureerbare
base-URL zitten.

## Queue-scoping

| Scope | Events |
| --- | --- |
| **Node** `.{nodeName}` | `ANALYZE_LIBRARY_REQUEST` |
| **Directory** `.{dirName}` | `NEW_DIRECTORIES_SCAN_REQUEST`, `FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `SUBTITLE_FILE_FOUND`, `IMAGE_FOUND`, `NFO_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA` (disk), `PRE_TRANSCODE_RECENTLY_WATCHED`, `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED` |
| **Globaal** | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `BOOK_FOUND`, `COMIC_SERIES_FOUND`, `CHAPTER_FOUND` (geen consumer), `PODCAST_FOUND` (geen consumer), `PODCAST_EPISODE_FOUND` (geen consumer), `PODCAST_REFRESH_REQUESTED`, `CONTINUE_WATCHING_REBUILD_REQUESTED`, `ANALYZE_DATA` (worker), `SEARCH_INDEX_REQUESTED`, `SEARCH_REINDEX_REQUESTED` |
| **Cache-directory** `.{nodeName}-cache-directory` | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (de download landt op de disk van die node) |

`PRE_TRANSCODE_RECENTLY_WATCHED` is gescoped op disknaam in plaats van directorynaam (zie
`TranscoderQueueNamingConfig`).

## Handler-referentie

| Handler | Module | Ontvangt | Verstuurt |
| --- | --- | --- | --- |
| `HandleNewDirectoriesScanRequested` | disk | `NEW_DIRECTORIES_SCAN_REQUEST` | `FILE_SCAN_REQUESTED` |
| `FileScanRequestedHandle` | disk | `FILE_SCAN_REQUESTED` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `EPUB_FILE_FOUND` / `COMIC_FILE_FOUND` / `IMAGE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandleMediaFileFound` | disk | `MEDIA_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleAudioFileFound` | disk | `AUDIO_FILE_FOUND` | `IMAGE_FOUND` (track- óf chapter-gebonden, per library-type) |
| `HandleEpubFileFound` | disk | `EPUB_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleComicFileFound` | disk | `COMIC_FILE_FOUND` | `IMAGE_FOUND` (geëxtraheerde cover) |
| `HandleSubtitleFileFound` | disk | `SUBTITLE_FILE_FOUND` | — |
| `HandleImageFound` | disk | `IMAGE_FOUND` | — |
| `HandleNfoFileFound` | disk | `NFO_FILE_FOUND` | — |
| `HandleUpdateImagesRequested` | disk | `UPDATE_IMAGES_REQUESTED` | `UPDATE_IMAGES_REQUESTED` (volgende chunk) |
| `HandleAnalyzeDataDisk` | disk | `ANALYZE_DATA` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandlePreTranscodeRecentlyWatched` | disk | `PRE_TRANSCODE_RECENTLY_WATCHED` | `TRANSCODE_REQUESTED`, `MEDIA_FILE_FOUND` (voor bestanden zonder geanalyseerde streams) |
| `HandlePersonFound` | disk | `PERSON_FOUND` | `NFO_FILE_FOUND` |
| `HandleAlbumFound` | disk | `ALBUM_FOUND` | `NFO_FILE_FOUND` |
| `HandlePodcastEpisodeDownloadRequested` | disk | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` | `AUDIO_FILE_FOUND` (op de cache-dir-queue → ffprobe + HLS-pregeneratie) |
| `AnalyzeLibraryRequestedHandle` | worker | `ANALYZE_LIBRARY_REQUEST` | `UPDATE_IMAGES_REQUESTED`, `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `AUDIO_FILE_FOUND` |
| `AnalyzeDataHandle` | worker | `ANALYZE_DATA` | cascade per entiteitstype |
| `HandleShowFound` | worker | `SHOW_FOUND` | `IMAGE_FOUND` (+ cast credits direct in de database) |
| `HandleEpisodeFound` | worker | `EPISODE_FOUND` | `IMAGE_FOUND` (+ cast/guest-star credits direct in de database) |
| `MovieFoundHandle` | worker | `MOVIE_FOUND` | `IMAGE_FOUND` (+ cast credits direct in de database) |
| `HandlePersonFound` | worker | `PERSON_FOUND` | — |
| `HandleAlbumFound` | worker | `ALBUM_FOUND` | `IMAGE_FOUND` |
| `HandleBookFound` | worker | `BOOK_FOUND` | `IMAGE_FOUND` (Open Library-cover, alleen als er nog geen is) |
| `HandleComicSeriesFound` | worker | `COMIC_SERIES_FOUND` | `IMAGE_FOUND` (Wikipedia-thumbnail, alleen zonder lokale artwork) |
| `HandlePodcastRefreshRequested` | worker | `PODCAST_REFRESH_REQUESTED` | `IMAGE_FOUND` (feed-cover), `PODCAST_EPISODE_FOUND`, `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (nieuwste N) |
| `HandleContinueWatchingRebuildRequested` | worker | `CONTINUE_WATCHING_REBUILD_REQUESTED` | — |
| `HandleTranscodeRequested` | transcoder | `TRANSCODE_REQUESTED` | `TRANSCODE_PASS_REQUESTED` |
| `HandleTranscodePassRequested` | transcoder | `TRANSCODE_PASS_REQUESTED` | — |
| `HandleSearchIndexRequested` | search | `SEARCH_INDEX_REQUESTED` | — (upsert/delete in Typesense) |
| `HandleSearchReindexRequested` | search | `SEARCH_REINDEX_REQUESTED` | — (volledige rebuild + alias-swap) |

`SEARCH_INDEX_REQUESTED` wordt op veel plekken verstuurd: `ServerEventService.createXFoundEvent`
(bij creatie), `MetadataSave` (TMDB), de MusicBrainz- en NFO-handlers, audio-tag-saves (inclusief
`action=DELETE` bij track-dedup) en metadata-deletes — zie [hoofdstuk 6](06-search.md).
