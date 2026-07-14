# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Ister Server** is a media server application (similar to Plex/Jellyfin) built with Spring Boot 4.0.6 and Java 25. It scans media libraries (movies, TV shows, music, books, **and podcasts**), fetches metadata from TMDB, and streams HLS-transcoded media to clients via REST and GraphQL APIs. It supports a **multi-node** deployment where one node transcodes media owned by another.

## Build & Run Commands

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew :worker:test --tests "app.ister.worker.events.subtitlefilefound.SubtitleFilePathParserTest"

# Run tests in a specific module
./gradlew :core:test

# Start required services (PostgreSQL + RabbitMQ) before running locally
podman-compose -f docker-compose-local.yml up database rabbitMQ

# Run the application
./gradlew bootRun

# Build Docker image
./gradlew bootBuildImage
```

## Module Structure

```
server/       - Spring Boot entry point (@SpringBootApplication, scanBasePackages="app.ister")
core/         - Shared infra: Handle<T> interface, MessageQueue base names, MessageSender, eventdata DTOs, Jaffree utils
database/     - JPA entities, repositories, and the EventType enum (NB: same package "app.ister.core.*" as core — split package)
api/          - REST controllers + GraphQL schema/resolvers
disk/         - File system scanning, startup tasks, media-file/image/subtitle/nfo/audio event handlers
worker/       - Async job handlers: metadata fetch (TMDB) for movie/show/episode, (MusicBrainz) for person/album
search/       - Optional Typesense full-text search: index event handlers + query service (package app.ister.search)
transcoder/   - FFmpeg-based HLS transcoding via Jaffree library
```

**Dependency flow:** `server` → `{api, disk, worker, search, transcoder}` → `database` → `core`; additionally `api` → `search` (no other internal deps).
Note: `core/` and `database/` both contribute to package `app.ister.core.*`; entities/repositories live in `database/`, not `core/`.

## Architecture: Event-Driven via RabbitMQ

All significant work is done asynchronously through RabbitMQ message queues. The central interface is `Handle<T extends MessageData>` (core module) which all event handlers implement.

**Event flow:**
1. Trigger (API call, startup, scan request) → `MessageSender.send()` → RabbitMQ queue
2. `Handle<T>` implementation in `disk/`, `worker/`, or `transcoder/` receives message → processes → may send further events

**Two enums, do not confuse them:**
- `EventType` (in `database/.../enums/EventType.java`) is the logical message type. `Handle.handles()` returns an `EventType`, and `listener()` rejects a message whose `eventType` doesn't match. This is the source of truth for "what kinds of events exist" (~19 values).
- `MessageQueue` (in `core/.../MessageQueue.java`) holds the queue **base names** (`app.ister.server.<event>`). `MessageSender` maps an event to its queue.

**Event categories** (see `EVENT_FLOWS.md` for full per-flow mermaid diagrams — keep it as the canonical reference):
- `FILE_SCAN_REQUESTED`, `NEW_DIRECTORIES_SCAN_REQUEST` — disk scanning
- `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `IMAGE_FOUND`, `SUBTITLE_FILE_FOUND`, `NFO_FILE_FOUND` — file-type-specific processing
- `MOVIE_FOUND`, `SHOW_FOUND`, `EPISODE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `TRACK_FOUND`, `BOOK_FOUND`, `CHAPTER_FOUND`, `EPUB_FILE_FOUND` — metadata fetching from TMDB (incl. cast credits) / MusicBrainz / Open Library (books) / tag or OPF parsing. TMDB details are fetched **once per configured language** (movie/show/episode handlers loop over `LanguageProperties.tags()`), producing one `MetadataEntity` row per language (`MetadataEntity.language` stored as ISO-639-3). A `PersonEntity` is both a music artist and an actor; TMDB cast members are deduplicated on exact name + birth year. A `CreditEntity` links a person to exactly one of movie/show/episode; the GraphQL `Credit` type exposes those back-references (`movie`/`show`/`episode`, batch-resolved in `CreditController`) so a person's filmography is queryable via `personById { credits { movie/show/episode } }`. **Books** (`LibraryType.BOOK`, structure `Author/Book.epub` + `Author/Book/NNN_Chapter.mp3`): one `BookEntity` per logical book (author = `PersonEntity`), formats are attachments — epubs link via `MediaFileEntity.bookEntity`, audiobook mp3s via `ChapterEntity` (streams over the same audio-only HLS path as tracks). `MediaFileEntity.mediaOverlays` marks EPUB 3 read-aloud epubs, detected from the epub CONTENTS (SMIL in the OPF manifest, parsed by `disk/.../epub/EpubParser`), never from the filename. Epubs are read lazily by the server-hosted reader web app (`/reader/`, api module) through `GET /epub/{mediaFileId}/resource/{entry}` (Range/ETag support, stream-token or cookie auth); reading position is a `WatchStatusEntity` with `readingLocation` (epubcfi) + `readingProgress`, synced via the `updateReadingProgress` mutation or `POST /reading-progress`. **Podcasts** (`LibraryType.PODCAST`) are the first FEED-based library type: no library directory; `subscribePodcast(feedUrl)` + an hourly `PodcastRefreshScheduler` drive `PODCAST_REFRESH_REQUESTED` → worker `RssFeedParser` (conditional GET, guid dedup, max 500 items) → episode rows; the newest N (`app.ister.worker.podcast.auto-download-count`, default 3) are downloaded to `{cache}/podcasts/` via `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (cache-directory-scoped queue) and then flow through the normal `AUDIO_FILE_FOUND` → HLS pipeline; older episodes download on demand (`downloadPodcastEpisode` mutation). The daily cache cleanup also expires podcast downloads (`podcast-retention-days`, default 30) unless someone is mid-episode. Directory search uses the free iTunes Search API (`ItunesSearchService`, api module).
- `ANALYZE_DATA`, `ANALYZE_LIBRARY_REQUEST`, `UPDATE_IMAGES_REQUESTED` — analysis & image refresh
- `CONTINUE_WATCHING_REBUILD_REQUESTED` — nightly rebuild of the continue-watching cache (see below)
- `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED`, `PRE_TRANSCODE_RECENTLY_WATCHED` — HLS transcoding (see below)
- `SEARCH_INDEX_REQUESTED`, `SEARCH_REINDEX_REQUESTED` — Typesense indexing (search module). The enabled flag (`app.ister.typesense.enabled`) is checked at **runtime** inside the handlers (disabled → events consumed and discarded), NOT via `@ConditionalOnProperty` — bean conditions are frozen at GraalVM native-image build time, so conditional beans would be baked out of the prod image. Module properties files imported via `spring.config.import` need a `META-INF/native-image/resource-config.json` in their module (see `search/src/main/resources/META-INF/native-image/`). Creation events come free via `ServerEventService.createXFoundEvent`; enrichment handlers emit after metadata saves; any code deleting a searchable entity must call `createSearchDeleteEvent`. GraphQL: `search(term)` returns a `SearchResult` union hydrated from PostgreSQL; `reindexSearch` rebuilds into a fresh collection + alias swap. **Multilingual:** the collection schema and `query_by` are generated from `LanguageProperties.tags()` — each configured language gets `title_<tag>`/`description_<tag>`/`genre_<tag>` fields with the matching Typesense `locale`; `SearchDocument` is a `Map<String,Object>` (not a fixed record) so the localized keys stay dynamic. Adding a language needs a re-scan (to create the new `MetadataEntity` rows) followed by `reindexSearch` (schema is fixed at collection creation). See [Languages](README.md#languages).

**Directory/node-scoped queues:** transcode queues are not global. `TranscoderQueueNamingConfig` appends the directory (or disk) name, e.g. `app.ister.server.transcode_requested.<directoryName>`. Each node listens only on the queues for the directories it owns, so transcode work routes to the node that holds the source file.

## Architecture: HLS Transcoding (transcoder module)

Streaming is HLS. `HlsService` + `HlsTranscodeService` coordinate playlist building and FFmpeg.

- **Playlists are generated up-front; segments are produced lazily.** A `GET .../master.m3u8` cache-miss sends `TRANSCODE_REQUESTED`; `HandleTranscodeRequested` → `generateAllPlaylists` writes master + per-stream `.m3u8` to `tmpDir/{mediaFileId}/`, and the HTTP thread polls until they exist.
- **One continuous FFmpeg pass per quality level**, not one process per segment. Each pass uses `-f segment -segment_times` so the encoder never resets PTS (avoids A/V drift). The first `.ts` request for a quality sends `TRANSCODE_PASS_REQUESTED`; segment requests poll the cache dir until the pass has written (and closed) that segment. Because passes encode sequentially from t=0, a forward seek waits for the encoder to catch up.
- **Concurrency is bounded:** `transcodeExecutor` (fixed 4 threads) + `concurrentFileSlots` semaphore (`max-concurrent-files`, default 2). A pass holds a thread for the whole file duration. Pre-transcoding (`startAllPasses`, triggered by `PRE_TRANSCODE_RECENTLY_WATCHED`) competes for the same pool — beware of starving interactive playback.
- **Multi-node:** for a remote file the transcode runs on the owner node; a watcher thread uploads each stable segment to the requesting node via `POST /transcode/upload/{id}/{fileName}` (`FileController`). `resolveInputPath` feeds FFmpeg a tokenized `…/download` URL when the file is remote.
- **Pre-transcoding is narrowed by the users' settings** (`PassFilter`): only audio streams in a preferred language (`user_settings.preferred_audio_languages`, merged over the users that pulled the file in; falls back to `app.ister.languages`) and only video variants up to `max_video_height`. It never produces the 64k audio bitrate — `HlsPlaylistBuilder` folds that group into 192k, so no master playlist ever references it. Interactive playback is unfiltered (`PassFilter.none()`): it must be able to serve any track a player asks for, and starts passes lazily anyway. A finished background pass pulls in the next pending pass of the same file, otherwise a dropped (budget-exhausted) pass would wait for the next pre-transcode cycle.

## Architecture: Continue Watching (the `recentlyWatched` query)

The continue-watching list is **precomputed**, not derived on read. `continue_watching` (V20) holds one row
per user per container — show / movie / book / podcast episode (`group_id`) — pointing at the item to resume
with. `ContinueWatchingService` (database module) owns it:

- **Incremental:** `onWatchStatusChanged(watchStatus)` is called *inside the transaction* of every watch-status
  write (`PlayQueueService.updateWatchStatus`, `BookController.updateReadingProgress`,
  `ReadingProgressController`). An unfinished item resumes itself; a finished one hands over to the next
  unwatched episode/chapter, found with a single indexed query (`EpisodeRepository.findNextUnwatchedEpisodeId`,
  `ChapterRepository.findNextUnfinishedChapterId`) — never by loading a whole show. **Any new code path that
  writes a `WatchStatusEntity` must call this**, or the list goes stale until the nightly rebuild.
- **All target columns NULL** means "nothing left to continue with". The row deliberately survives: when the
  scanner later adds an episode, `recomputeForShow` (called from `ScannerHelperService.getOrCreateEpisode`)
  makes that new episode the target and the show reappears in the list.
- **Self-healing:** `ContinueWatchingRebuildScheduler` (worker) queues `CONTINUE_WATCHING_REBUILD_REQUESTED`
  per user nightly, and once at startup while the table is empty (the backfill after V20). `rebuildForUser`
  throws the user's rows away and recomputes them from `watch_status_entity`, which also prunes entries whose
  media is gone.
- Writes go through a native `INSERT … ON CONFLICT DO UPDATE` upsert (`ContinueWatchingRepository.upsert`):
  two concurrent heartbeats of one user must not fail on a unique-constraint race. `last_watched` only moves
  forward (`GREATEST`).
- `PreTranscodeService` reads the same table — the entries *are* the "what will they play next" set — instead
  of walking the watch history itself.

## Startup Initialization

`disk/.../StartupTasks` handles `ContextRefreshedEvent` to:
- Create/update `NodeEntity`, `LibraryEntity`, `DirectoryEntity` from config properties
- Create cache directories on disk
- Validate multi-node configuration

Libraries and directories are configured in `disk/src/main/resources/disk.properties` (or via env vars).

## Key Configuration

**application.properties** (server module) imports: `core.properties`, `disk.properties`, `git.properties`

Essential runtime config:
- PostgreSQL: `spring.datasource.url` (default: localhost/ister)
- RabbitMQ: `spring.rabbitmq.*` (default: localhost, user/password)
- FFmpeg binary paths, cache/temp directories
- TMDB API key: `app.ister.server.tmdb.api-key`
- Supported languages (app-wide): `app.ister.languages` / `ISTER_LANGUAGES` (ISO-639-1 tags, default `en,nl`) — drives TMDB fetch + search indexing, see README "Languages"
- OAuth2/OIDC: `OIDC_URL` env var

**Local dev credentials** (docker-compose-local.yml): DB = ister/ister, RabbitMQ = user/password

## API Surface

- **REST**: controllers under `api/` for movies, shows, episodes, seasons, music (person/album/track), scanner, play queue, watch status, stream tokens, server info. Errors are mapped centrally in `api/.../error/` (`RestExceptionHandler`, `GraphQlExceptionResolver`).
- **GraphQL**: Schema at `api/src/main/resources/graphql/schema.graphqls`; GraphQL IDE enabled in dev
- **Auth**: OAuth2 JWT via Spring Security Resource Server (Keycloak-compatible OIDC). HLS segment/playlist requests can also authenticate via a short-lived `?token=` stream token (`StreamTokenAuthenticationFilter`), which is injected into playlist URIs server-side.

## Testing Notes

- Unit tests use JUnit 5 + Mockito
- `jimfs` (in-memory filesystem) used in disk/file-path tests
- Integration tests use Testcontainers PostgreSQL (set `DOCKER_HOST` to the podman socket locally); production uses PostgreSQL
- SonarQube + Jacoco configured for CI coverage reporting
