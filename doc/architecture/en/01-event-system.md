# Event system

Everything significant runs asynchronously through RabbitMQ. A trigger (API call, scheduler, scan)
calls `MessageSender.send()`, which routes the event to its queue; a `Handle<T>` implementation in
`disk/`, `worker/`, `search/` or `transcoder/` consumes it, does its work, and may send further
events. The [full event overview](../diagrams/event-overview.md) shows how the main triggers fan out
through the handlers.

## The `Handle<T>` contract

`Handle<T extends MessageData>` (core module) is the central interface. `handles()` returns the
`EventType` the handler owns; the default `listener()` rejects any message whose `eventType` field
does not match before dispatching to `handle()`. Handlers are plain Spring beans with a
`@RabbitListener` on their queue.

**No Hibernate session on listener threads.** RabbitMQ listener threads have no open session, so
lazy association navigation throws `LazyInitializationException`. Handlers must load what they need
with explicit repository queries (fetch joins or dedicated finder methods), never by walking entity
graphs.

## Two enums, do not confuse them

- **`EventType`** (`database/.../enums/EventType.java`) is the logical message type — the source of
  truth for what kinds of events exist (31 values). `Handle.handles()` returns one.
- **`MessageQueue`** (`core/.../MessageQueue.java`) holds the queue **base names**. `MessageSender`
  maps an event to its queue.

Queue names follow `app.ister.server.<event>[.<scope>]`, where the scope is a directory name, node
name, or absent for global queues. Scoping is what routes work to the node that owns the files: each
node declares and listens only on the queues for its own directories.

## Retries and dead-lettering

Failed listeners retry with exponential backoff (`spring.rabbitmq.listener.simple.retry.*` in
`core.properties`: 3 attempts, 2s initial interval, multiplier 2). After the final failure a
`RepublishMessageRecoverer` moves the message to the **`app.ister.server.dead-letter`** queue with
the exception preserved in the message headers (`RabbitReliabilityConfig`). Recent failures also
feed the `RecentFailuresBuffer` for the status subscriptions ([chapter
5](05-continue-watching-and-status.md)). The Helm chart's e2e fails on any dead-lettered event,
which is why every external call must sit behind a configurable base URL.

## Queue scoping

| Scope | Events |
| --- | --- |
| **Node** `.{nodeName}` | `ANALYZE_LIBRARY_REQUEST` |
| **Directory** `.{dirName}` | `NEW_DIRECTORIES_SCAN_REQUEST`, `FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `SUBTITLE_FILE_FOUND`, `IMAGE_FOUND`, `NFO_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA` (disk), `PRE_TRANSCODE_RECENTLY_WATCHED`, `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED` |
| **Global** | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `BOOK_FOUND`, `COMIC_SERIES_FOUND`, `CHAPTER_FOUND` (no consumer), `PODCAST_FOUND` (no consumer), `PODCAST_EPISODE_FOUND` (no consumer), `PODCAST_REFRESH_REQUESTED`, `CONTINUE_WATCHING_REBUILD_REQUESTED`, `ANALYZE_DATA` (worker), `SEARCH_INDEX_REQUESTED`, `SEARCH_REINDEX_REQUESTED` |
| **Cache directory** `.{nodeName}-cache-directory` | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (the download lands on that node's disk) |

`PRE_TRANSCODE_RECENTLY_WATCHED` is scoped by disk name rather than directory name (see
`TranscoderQueueNamingConfig`).

## Handler reference

| Handler | Module | Receives | Sends |
| --- | --- | --- | --- |
| `HandleNewDirectoriesScanRequested` | disk | `NEW_DIRECTORIES_SCAN_REQUEST` | `FILE_SCAN_REQUESTED` |
| `FileScanRequestedHandle` | disk | `FILE_SCAN_REQUESTED` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `EPUB_FILE_FOUND` / `COMIC_FILE_FOUND` / `IMAGE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandleMediaFileFound` | disk | `MEDIA_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleAudioFileFound` | disk | `AUDIO_FILE_FOUND` | `IMAGE_FOUND` (track- or chapter-bound, by library type) |
| `HandleEpubFileFound` | disk | `EPUB_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleComicFileFound` | disk | `COMIC_FILE_FOUND` | `IMAGE_FOUND` (extracted cover) |
| `HandleSubtitleFileFound` | disk | `SUBTITLE_FILE_FOUND` | — |
| `HandleImageFound` | disk | `IMAGE_FOUND` | — |
| `HandleNfoFileFound` | disk | `NFO_FILE_FOUND` | — |
| `HandleUpdateImagesRequested` | disk | `UPDATE_IMAGES_REQUESTED` | `UPDATE_IMAGES_REQUESTED` (next chunk) |
| `HandleAnalyzeDataDisk` | disk | `ANALYZE_DATA` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandlePreTranscodeRecentlyWatched` | disk | `PRE_TRANSCODE_RECENTLY_WATCHED` | `TRANSCODE_REQUESTED`, `MEDIA_FILE_FOUND` (for files without analyzed streams) |
| `HandlePersonFound` | disk | `PERSON_FOUND` | `NFO_FILE_FOUND` |
| `HandleAlbumFound` | disk | `ALBUM_FOUND` | `NFO_FILE_FOUND` |
| `HandlePodcastEpisodeDownloadRequested` | disk | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` | `AUDIO_FILE_FOUND` (on the cache-dir queue → ffprobe + HLS pre-generation) |
| `AnalyzeLibraryRequestedHandle` | worker | `ANALYZE_LIBRARY_REQUEST` | `UPDATE_IMAGES_REQUESTED`, `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `AUDIO_FILE_FOUND` |
| `AnalyzeDataHandle` | worker | `ANALYZE_DATA` | cascade per entity type |
| `HandleShowFound` | worker | `SHOW_FOUND` | `IMAGE_FOUND` (+ cast credits written directly to the database) |
| `HandleEpisodeFound` | worker | `EPISODE_FOUND` | `IMAGE_FOUND` (+ cast/guest-star credits directly to the database) |
| `MovieFoundHandle` | worker | `MOVIE_FOUND` | `IMAGE_FOUND` (+ cast credits directly to the database) |
| `HandlePersonFound` | worker | `PERSON_FOUND` | — |
| `HandleAlbumFound` | worker | `ALBUM_FOUND` | `IMAGE_FOUND` |
| `HandleBookFound` | worker | `BOOK_FOUND` | `IMAGE_FOUND` (Open Library cover, only when none exists yet) |
| `HandleComicSeriesFound` | worker | `COMIC_SERIES_FOUND` | `IMAGE_FOUND` (Wikipedia thumbnail, only when no local artwork) |
| `HandlePodcastRefreshRequested` | worker | `PODCAST_REFRESH_REQUESTED` | `IMAGE_FOUND` (feed cover), `PODCAST_EPISODE_FOUND`, `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (newest N) |
| `HandleContinueWatchingRebuildRequested` | worker | `CONTINUE_WATCHING_REBUILD_REQUESTED` | — |
| `HandleTranscodeRequested` | transcoder | `TRANSCODE_REQUESTED` | `TRANSCODE_PASS_REQUESTED` |
| `HandleTranscodePassRequested` | transcoder | `TRANSCODE_PASS_REQUESTED` | — |
| `HandleSearchIndexRequested` | search | `SEARCH_INDEX_REQUESTED` | — (upsert/delete in Typesense) |
| `HandleSearchReindexRequested` | search | `SEARCH_REINDEX_REQUESTED` | — (full rebuild + alias swap) |

`SEARCH_INDEX_REQUESTED` is emitted from many places: `ServerEventService.createXFoundEvent` (on
creation), `MetadataSave` (TMDB), the MusicBrainz and NFO handlers, audio-tag saves (including
`action=DELETE` on track dedup), and metadata deletes — see [chapter 6](06-search.md).


