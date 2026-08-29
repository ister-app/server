---
description: "Hoe het RabbitMQ-eventsysteem van Ister werkt: het Handle-contract, EventType versus MessageQueue, queues per directory, retries en dead-lettering."
---

# Eventsysteem

Alles wat significant is, loopt asynchroon via RabbitMQ. Een trigger (API-call, scheduler, scan)
roept `MessageSender.send()` aan, die het event naar zijn queue routeert; een
`Handle<T>`-implementatie in `disk/`, `worker/`, `search/` of `transcoder/` consumeert het, doet
zijn werk en kan verdere events versturen. Het [volledige eventoverzicht](../diagrams/event-overview.md)
laat zien hoe de belangrijkste triggers uitwaaieren over de handlers.

## Het `Handle<T>`-contract

`Handle<T extends MessageData>` (core-module) is de centrale interface. `handles()` geeft het
`EventType` terug dat de handler bezit; de standaard-`listener()` gooit een
`IllegalArgumentException` voor elk bericht waarvan het `eventType`-veld niet overeenkomt voordat
er naar `handle()` gedispatcht wordt — zo'n bericht belandt dus na de retries op de
dead-letter-queue. Handlers zijn gewone Spring-beans met een `@RabbitListener` op hun queue.

**Geen Hibernate-sessie op listener-threads.** RabbitMQ-listener-threads hebben geen open sessie,
dus lazy navigeren over associaties gooit een `LazyInitializationException`. Handlers moeten wat ze
nodig hebben expliciet laden met repository-queries (fetch joins of speciale finder-methodes),
nooit door entity-grafen af te lopen.

## Twee enums, haal ze niet door elkaar

- **`EventType`** (`database/.../enums/EventType.java`) is het logische berichttype — de bron van
  waarheid voor welke soorten events er bestaan (32 waarden). `Handle.handles()` geeft er één terug.
- **`MessageQueue`** (`core/.../MessageQueue.java`) bevat de **basisnamen** van de queues.
  `MessageSender` mapt een event naar zijn queue.

Queue-namen volgen `app.ister.server.<Event>[.<scope>]`, waarbij de scope een directorynaam of
nodenaam is, of ontbreekt bij globale queues. Het event-deel is **PascalCase** — de echte queues
heten `app.ister.server.MediaFileFound`, `app.ister.server.TranscodeRequested.<dirNaam>` enzovoort
(zie `MessageQueue.java`) — handig om te weten wanneer je in de RabbitMQ-management-UI grept. Die
scoping is wat werk routeert naar de node die de bestanden bezit: elke node declareert en
beluistert alleen de queues van zijn eigen directories.

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
| **Node** `.{nodeName}` | `PERSON_FOUND`, `ALBUM_FOUND` — de node-gescopete sends (`MessageSender`) die de **disk**-handlers bereiken op de node met de bestanden (herparse van artiest-/album-`.nfo` en map-artwork); de onderhoudsflows dispatchen ze via `worker/.../FoundEventDispatcher`. Dezelfde events hebben óók globale sends voor de verrijkingshandlers van de worker (zie hieronder). |
| **Directory** `.{dirName}` | `NEW_DIRECTORIES_SCAN_REQUEST`, `FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `SUBTITLE_FILE_FOUND`, `IMAGE_FOUND`, `NFO_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA` (disk), `DETECT_SEGMENTS`, `PRE_TRANSCODE_RECENTLY_WATCHED`, `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED` |
| **Globaal** | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND` (worker), `ALBUM_FOUND` (worker), `TRACK_FOUND` (geen consumer), `BOOK_FOUND`, `COMIC_SERIES_FOUND`, `CHAPTER_FOUND` (geen consumer), `PODCAST_FOUND` (geen consumer), `PODCAST_EPISODE_FOUND` (geen consumer), `PODCAST_REFRESH_REQUESTED`, `CONTINUE_WATCHING_REBUILD_REQUESTED`, `ANALYZE_DATA` (worker), `METADATA_BACKFILL_REQUESTED`, `SEARCH_INDEX_REQUESTED`, `SEARCH_REINDEX_REQUESTED` |
| **Cache-directory** `.{nodeName}-cache-directory` | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` bestaat **alleen** met deze suffix (de download landt op de disk van die node). Daarnaast krijgt bijna elke directory-gescopete queue óók een cache-directory-variant: `DiskQueueNamingConfig` voegt er één toe voor elk van zijn queues (`FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `IMAGE_FOUND`, `SUBTITLE_FILE_FOUND`, `NFO_FILE_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA`, `DETECT_SEGMENTS`, `PRE_TRANSCODE_RECENTLY_WATCHED`, …), en `TranscoderQueueNamingConfig` doet hetzelfde voor de transcode-queues — gedownloade podcastafleveringen staan in de cache-directory en moeten door dezelfde pipelines. |

`PRE_TRANSCODE_RECENTLY_WATCHED` krijgt de **directorynaam** als suffix: `PreTranscodeScheduler`
(worker) stuurt één event per geconfigureerde directory (`WorkerDiskConfig`, dat
`app.ister.disk.directories` leest), en de disk-module beluistert de bijbehorende queues
(`DiskQueueNamingConfig.getPreTranscodeRecentlyWatchedQueues`). Alleen `TRANSCODE_REQUESTED` en
`TRANSCODE_PASS_REQUESTED` kunnen terugvallen op de `app.ister.transcoder.disks`-namen wanneer er
disks geconfigureerd zijn (`TranscoderQueueNamingConfig`).

## Handler-referentie

| Handler | Module | Ontvangt | Verstuurt |
| --- | --- | --- | --- |
| `HandleNewDirectoriesScanRequested` | disk | `NEW_DIRECTORIES_SCAN_REQUEST` | `FILE_SCAN_REQUESTED` |
| `FileScanRequestedHandle` | disk | `FILE_SCAN_REQUESTED` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `EPUB_FILE_FOUND` / `COMIC_FILE_FOUND` / `IMAGE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandleMediaFileFound` | disk | `MEDIA_FILE_FOUND` | `IMAGE_FOUND`, `DETECT_SEGMENTS` (per seizoen, na commit) |
| `HandleDetectSegments` | disk | `DETECT_SEGMENTS` | `DETECT_SEGMENTS` (intro-/outro-detectie per seizoen, in chunks verwerkt — de handler zet zichzelf opnieuw in de queue voor de volgende chunk) |
| `HandleAudioFileFound` | disk | `AUDIO_FILE_FOUND` | `IMAGE_FOUND` (track- óf chapter-gebonden, per library-type) |
| `HandleEpubFileFound` | disk | `EPUB_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleComicFileFound` | disk | `COMIC_FILE_FOUND` | `IMAGE_FOUND` (geëxtraheerde cover) |
| `HandleSubtitleFileFound` | disk | `SUBTITLE_FILE_FOUND` | — |
| `HandleImageFound` | disk | `IMAGE_FOUND` | — |
| `HandleNfoFileFound` | disk | `NFO_FILE_FOUND` | — |
| `HandleUpdateImagesRequested` | disk | `UPDATE_IMAGES_REQUESTED` | `UPDATE_IMAGES_REQUESTED` (volgende chunk) |
| `HandleAnalyzeDataDisk` | disk | `ANALYZE_DATA` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandlePreTranscodeRecentlyWatched` | disk | `PRE_TRANSCODE_RECENTLY_WATCHED` | `TRANSCODE_REQUESTED`, `MEDIA_FILE_FOUND` (voor bestanden zonder geanalyseerde streams) |
| `HandlePersonFound` | disk | `PERSON_FOUND` (node-gescopete queue) | `NFO_FILE_FOUND` |
| `HandleAlbumFound` | disk | `ALBUM_FOUND` (node-gescopete queue) | `NFO_FILE_FOUND`, `FILE_SCAN_REQUESTED` (heringest van lokale albumartwork zoals `cover.jpg`) |
| `HandlePodcastEpisodeDownloadRequested` | disk | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` | `AUDIO_FILE_FOUND` (op de cache-dir-queue → ffprobe + HLS-pregeneratie) |
| `MetadataBackfillHandle` | worker | `METADATA_BACKFILL_REQUESTED` | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `AUDIO_FILE_FOUND`, `BOOK_FOUND`, `COMIC_SERIES_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `NFO_FILE_FOUND` |
| `AnalyzeDataHandle` | worker | `ANALYZE_DATA` | cascade per entiteitstype |
| `HandleShowFound` | worker | `SHOW_FOUND` | `IMAGE_FOUND` (+ cast credits direct in de database) |
| `HandleEpisodeFound` | worker | `EPISODE_FOUND` | `IMAGE_FOUND` (+ cast/guest-star credits direct in de database) |
| `MovieFoundHandle` | worker | `MOVIE_FOUND` | `IMAGE_FOUND` (+ cast credits direct in de database) |
| `HandlePersonFound` | worker | `PERSON_FOUND` (globale queue) | — |
| `HandleAlbumFound` | worker | `ALBUM_FOUND` (globale queue) | `IMAGE_FOUND` |
| `HandleBookFound` | worker | `BOOK_FOUND` | `IMAGE_FOUND` (Open Library-cover, alleen als er nog geen is) |
| `HandleComicSeriesFound` | worker | `COMIC_SERIES_FOUND` | `IMAGE_FOUND` (Wikipedia-thumbnail, alleen zonder lokale artwork) |
| `HandlePodcastRefreshRequested` | worker | `PODCAST_REFRESH_REQUESTED` | `IMAGE_FOUND` (feed-cover), `PODCAST_EPISODE_FOUND`, `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (nieuwste N) |
| `HandleContinueWatchingRebuildRequested` | worker | `CONTINUE_WATCHING_REBUILD_REQUESTED` | — |
| `HandleTranscodeRequested` | transcoder | `TRANSCODE_REQUESTED` | `TRANSCODE_PASS_REQUESTED` |
| `HandleTranscodePassRequested` | transcoder | `TRANSCODE_PASS_REQUESTED` | — |
| `HandleSearchIndexRequested` | search | `SEARCH_INDEX_REQUESTED` | — (upsert/delete in Typesense) |
| `HandleSearchReindexRequested` | search | `SEARCH_REINDEX_REQUESTED` | — (volledige rebuild + alias-swap) |

**Publiceren na commit.** Handlers die rijen verwijderen of schrijven en daarna een event sturen
dat naar die rijen wijst, moeten publiceren via `AfterCommitPublisher.publishAfterCommit` (core;
`ServerEventService` gebruikt hem voor elk `create*FoundEvent`): de consumer draait vaak binnen
milliseconden en zou anders de pre-commit-toestand lezen — bijvoorbeeld een album-found-consumer
die image-rijen ziet staan die de analyse-transactie op het punt staat te verwijderen, en daarom
de cover-refetch overslaat. De wrap zit bewust op de call sites en niet in `MessageSender`:
meerdere handlers registreren zelf al after-commit-callbacks, en een synchronization die vanuit
een `afterCommit`-callback geregistreerd wordt, wordt nooit meer aangeroepen — berichten zouden
dan stil verloren gaan.

`SEARCH_INDEX_REQUESTED` wordt op veel plekken verstuurd: `ServerEventService.createXFoundEvent`
(bij creatie), `MetadataSave` (TMDB), de MusicBrainz- en NFO-handlers, audio-tag-saves (inclusief
`action=DELETE` bij track-dedup) en metadata-deletes — zie [hoofdstuk 6](06-search.md).
