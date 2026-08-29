---
description: "How Ister's RabbitMQ event system works: the Handle contract, EventType versus MessageQueue, directory-scoped queues, retries and dead-lettering."
---

# Event system

Everything significant runs asynchronously through RabbitMQ. A trigger (API call, scheduler, scan)
calls `MessageSender.send()`, which routes the event to its queue; a `Handle<T>` implementation in
`disk/`, `worker/`, `search/` or `transcoder/` consumes it, does its work, and may send further
events. The [full event overview](../diagrams/event-overview.md) shows how the main triggers fan out
through the handlers.

## The `Handle<T>` contract

`Handle<T extends MessageData>` (core module) is the central interface. `handles()` returns the
`EventType` the handler owns; the default `listener()` throws an `IllegalArgumentException` for any
message whose `eventType` field does not match before dispatching to `handle()` — so after the
retries such a message ends up on the dead-letter queue. Handlers are plain Spring beans with a
`@RabbitListener` on their queue.

**No Hibernate session on listener threads.** RabbitMQ listener threads have no open session, so
lazy association navigation throws `LazyInitializationException`. Handlers must load what they need
with explicit repository queries (fetch joins or dedicated finder methods), never by walking entity
graphs.

## Two enums, do not confuse them

- **`EventType`** (`database/.../enums/EventType.java`) is the logical message type — the source of
  truth for what kinds of events exist (32 values). `Handle.handles()` returns one.
- **`MessageQueue`** (`core/.../MessageQueue.java`) holds the queue **base names**. `MessageSender`
  maps an event to its queue.

Queue names follow `app.ister.server.<Event>[.<scope>]`, where the scope is a directory name, node
name, or absent for global queues. The event part is **PascalCase** — the actual queues are named
`app.ister.server.MediaFileFound`, `app.ister.server.TranscodeRequested.<dirName>` and so on (see
`MessageQueue.java`) — worth knowing when grepping the RabbitMQ management UI. Scoping is what
routes work to the node that owns the files: each node declares and listens only on the queues for
its own directories.

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
| **Node** `.{nodeName}` | `PERSON_FOUND`, `ALBUM_FOUND` — the node-scoped sends (`MessageSender`) that reach the **disk** handlers on the node holding the files (artist/album `.nfo` and folder-artwork re-parse); the maintenance flows dispatch them via `worker/.../FoundEventDispatcher`. The same events also have global sends for the worker's enrichment handlers (see below). |
| **Directory** `.{dirName}` | `NEW_DIRECTORIES_SCAN_REQUEST`, `FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `SUBTITLE_FILE_FOUND`, `IMAGE_FOUND`, `NFO_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA` (disk), `DETECT_SEGMENTS`, `PRE_TRANSCODE_RECENTLY_WATCHED`, `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED` |
| **Global** | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND` (worker), `ALBUM_FOUND` (worker), `TRACK_FOUND` (no consumer), `BOOK_FOUND`, `COMIC_SERIES_FOUND`, `CHAPTER_FOUND` (no consumer), `PODCAST_FOUND` (no consumer), `PODCAST_EPISODE_FOUND` (no consumer), `PODCAST_REFRESH_REQUESTED`, `CONTINUE_WATCHING_REBUILD_REQUESTED`, `ANALYZE_DATA` (worker), `METADATA_BACKFILL_REQUESTED`, `SEARCH_INDEX_REQUESTED`, `SEARCH_REINDEX_REQUESTED` |
| **Cache directory** `.{nodeName}-cache-directory` | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` exists **only** with this suffix (the download lands on that node's disk). Beyond that, nearly every directory-scoped queue also gets a cache-directory variant: `DiskQueueNamingConfig` adds one for each of its queues (`FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `IMAGE_FOUND`, `SUBTITLE_FILE_FOUND`, `NFO_FILE_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA`, `DETECT_SEGMENTS`, `PRE_TRANSCODE_RECENTLY_WATCHED`, …), and `TranscoderQueueNamingConfig` does the same for the transcode queues — downloaded podcast episodes live in the cache directory and must flow through the same pipelines. |

`PRE_TRANSCODE_RECENTLY_WATCHED` is suffixed with the **directory name**: `PreTranscodeScheduler`
(worker) sends one event per configured directory (`WorkerDiskConfig`, reading
`app.ister.disk.directories`), and the disk module listens on the matching queues
(`DiskQueueNamingConfig.getPreTranscodeRecentlyWatchedQueues`). Only `TRANSCODE_REQUESTED` and
`TRANSCODE_PASS_REQUESTED` can fall back to the `app.ister.transcoder.disks` names when disks are
configured (`TranscoderQueueNamingConfig`).

## Handler reference

| Handler | Module | Receives | Sends |
| --- | --- | --- | --- |
| `HandleNewDirectoriesScanRequested` | disk | `NEW_DIRECTORIES_SCAN_REQUEST` | `FILE_SCAN_REQUESTED` |
| `FileScanRequestedHandle` | disk | `FILE_SCAN_REQUESTED` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `EPUB_FILE_FOUND` / `COMIC_FILE_FOUND` / `IMAGE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandleMediaFileFound` | disk | `MEDIA_FILE_FOUND` | `IMAGE_FOUND`, `DETECT_SEGMENTS` (season-scoped, after commit) |
| `HandleDetectSegments` | disk | `DETECT_SEGMENTS` | `DETECT_SEGMENTS` (intro/outro detection per season, processed in chunks — the handler re-queues itself for the next chunk) |
| `HandleAudioFileFound` | disk | `AUDIO_FILE_FOUND` | `IMAGE_FOUND` (track- or chapter-bound, by library type) |
| `HandleEpubFileFound` | disk | `EPUB_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleComicFileFound` | disk | `COMIC_FILE_FOUND` | `IMAGE_FOUND` (extracted cover) |
| `HandleSubtitleFileFound` | disk | `SUBTITLE_FILE_FOUND` | — |
| `HandleImageFound` | disk | `IMAGE_FOUND` | — |
| `HandleNfoFileFound` | disk | `NFO_FILE_FOUND` | — |
| `HandleUpdateImagesRequested` | disk | `UPDATE_IMAGES_REQUESTED` | `UPDATE_IMAGES_REQUESTED` (next chunk) |
| `HandleAnalyzeDataDisk` | disk | `ANALYZE_DATA` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandlePreTranscodeRecentlyWatched` | disk | `PRE_TRANSCODE_RECENTLY_WATCHED` | `TRANSCODE_REQUESTED`, `MEDIA_FILE_FOUND` (for files without analyzed streams) |
| `HandlePersonFound` | disk | `PERSON_FOUND` (node-scoped queue) | `NFO_FILE_FOUND` |
| `HandleAlbumFound` | disk | `ALBUM_FOUND` (node-scoped queue) | `NFO_FILE_FOUND`, `FILE_SCAN_REQUESTED` (re-ingest of local album artwork such as `cover.jpg`) |
| `HandlePodcastEpisodeDownloadRequested` | disk | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` | `AUDIO_FILE_FOUND` (on the cache-dir queue → ffprobe + HLS pre-generation) |
| `MetadataBackfillHandle` | worker | `METADATA_BACKFILL_REQUESTED` | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `AUDIO_FILE_FOUND`, `BOOK_FOUND`, `COMIC_SERIES_FOUND`, `EPUB_FILE_FOUND`, `COMIC_FILE_FOUND`, `NFO_FILE_FOUND` |
| `AnalyzeDataHandle` | worker | `ANALYZE_DATA` | cascade per entity type |
| `HandleShowFound` | worker | `SHOW_FOUND` | `IMAGE_FOUND` (+ cast credits written directly to the database) |
| `HandleEpisodeFound` | worker | `EPISODE_FOUND` | `IMAGE_FOUND` (+ cast/guest-star credits directly to the database) |
| `MovieFoundHandle` | worker | `MOVIE_FOUND` | `IMAGE_FOUND` (+ cast credits directly to the database) |
| `HandlePersonFound` | worker | `PERSON_FOUND` (global queue) | — |
| `HandleAlbumFound` | worker | `ALBUM_FOUND` (global queue) | `IMAGE_FOUND` |
| `HandleBookFound` | worker | `BOOK_FOUND` | `IMAGE_FOUND` (Open Library cover, only when none exists yet) |
| `HandleComicSeriesFound` | worker | `COMIC_SERIES_FOUND` | `IMAGE_FOUND` (Wikipedia thumbnail, only when no local artwork) |
| `HandlePodcastRefreshRequested` | worker | `PODCAST_REFRESH_REQUESTED` | `IMAGE_FOUND` (feed cover), `PODCAST_EPISODE_FOUND`, `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (newest N) |
| `HandleContinueWatchingRebuildRequested` | worker | `CONTINUE_WATCHING_REBUILD_REQUESTED` | — |
| `HandleTranscodeRequested` | transcoder | `TRANSCODE_REQUESTED` | `TRANSCODE_PASS_REQUESTED` |
| `HandleTranscodePassRequested` | transcoder | `TRANSCODE_PASS_REQUESTED` | — |
| `HandleSearchIndexRequested` | search | `SEARCH_INDEX_REQUESTED` | — (upsert/delete in Typesense) |
| `HandleSearchReindexRequested` | search | `SEARCH_REINDEX_REQUESTED` | — (full rebuild + alias swap) |

**Publish after commit.** Handlers that delete or write rows and then emit an event pointing at
those rows must publish via `AfterCommitPublisher.publishAfterCommit` (core; `ServerEventService`
uses it for every `create*FoundEvent`): the consumer often runs within milliseconds and would
otherwise read pre-commit state — e.g. an album-found consumer seeing image rows that the analysis
transaction is about to delete, and skipping the cover refetch. The wrap deliberately lives at the
call sites, not inside `MessageSender`: several handlers register their own after-commit callbacks,
and a synchronization registered from within an `afterCommit` callback is never invoked, which would
silently drop messages.

`SEARCH_INDEX_REQUESTED` is emitted from many places: `ServerEventService.createXFoundEvent` (on
creation), `MetadataSave` (TMDB), the MusicBrainz and NFO handlers, audio-tag saves (including
`action=DELETE` on track dedup), and metadata deletes — see [chapter 6](06-search.md).


