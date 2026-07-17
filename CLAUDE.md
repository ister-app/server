# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Ister Server** is a media server application (similar to Plex/Jellyfin) built with Spring Boot 4.0.6 and Java 25. It scans media libraries (movies, TV shows, music, books, comics, **and podcasts**), fetches metadata from TMDB, and streams HLS-transcoded media to clients via REST and GraphQL APIs. It supports a **multi-node** deployment where one node transcodes media owned by another.

## Documentation (`doc/`)

Structured documentation lives under `doc/`, mirroring the player repo's setup: `doc/admin/{en,nl}/`
(operator guide, 8 numbered chapters) and `doc/architecture/{en,nl}/` (developer docs, 9 numbered
chapters), plus `doc/architecture/diagrams/` (hand-authored mermaid, English only, shared by both
locales). Root `EVENT_FLOWS.md` is only a pointer stub to it now.

Rules when touching `doc/`:
- **EN/NL parity is CI-enforced**: `en/` and `nl/` must carry the same filenames, and any content
  change must be applied to both languages (the NL chapters are adaptations, not machine output).
- `ci/build-docs.sh --check` validates parity, relative links and mermaid fences (the `docs` job in
  `build.yml` runs it on every PR); without `--check` it packages `server-docs-<version>.zip`, which
  `release.yml` attaches to each GitHub release. The zip is gitignored.
- Plain markdown, no front-matter, relative links only; chapters are `NN-name.md`.
- A behavior change in the server that is described in `doc/` should update the affected chapters
  in the same PR.

## Commits & Releases

Commit subjects **must** be [Conventional Commits](https://www.conventionalcommits.org/)
(`feat(scope): …`, `fix: …`, `chore(deps): …`, `!` or `BREAKING CHANGE:` for breaking). A
`commit-lint` job fails the PR otherwise, and `.github/workflows/release.yml` derives both the
version bump and the release notes from them. See `CONTRIBUTING.md`.

Releases are cut nightly and automatically. **Never bump `version` in `build.gradle` by hand**:
`main` always carries a `-SNAPSHOT`, and only the tagged release commit carries a clean version.

## Build & Run Commands

```bash
# Build all modules
./gradlew build

# Run tests (ffmpeg must be on PATH — the transcoder tests shell out to it, as CI does)
./gradlew test

# Run a single test class
./gradlew :worker:test --tests "app.ister.worker.events.subtitlefilefound.SubtitleFilePathParserTest"

# Run tests in a specific module
./gradlew :core:test

# What CI actually runs (build.yml, "Build and analyze")
./gradlew build check jacocoTestReport sonar

# Start required services (PostgreSQL + RabbitMQ) before running locally
podman-compose -f docker-compose-local.yml up database rabbitMQ

# Run the application
./gradlew bootRun

# Build Docker image / GraalVM native image (Dockerfile.native)
./gradlew bootBuildImage
./gradlew nativeCompile
```

There is no separate formatter/linter: quality gating is SonarCloud (`org.sonarqube`) + Jacoco, run
as part of `check`. Integration tests are not a separate source set or task — they run under `test`
and pull PostgreSQL via Testcontainers.

Schema is Flyway, `database/src/main/resources/db/migration/V<n>__<name>.sql` (latest: `V23`).
Migrations are forward-only and also shipped as `Dockerfile.migrations`; never edit an applied one.

## Module Structure

```
server/       - Spring Boot entry point (@SpringBootApplication, scanBasePackages="app.ister")
core/         - Shared infra: Handle<T> interface, MessageQueue base names, MessageSender, eventdata DTOs, Jaffree utils
database/     - JPA entities, repositories, and the EventType enum (NB: same package "app.ister.core.*" as core — split package)
api/          - REST controllers + GraphQL schema/resolvers
disk/         - File system scanning, startup tasks, media-file/image/subtitle/nfo/audio/epub/comic event handlers
worker/       - Async job handlers: metadata fetch — TMDB (movie/show/episode), MusicBrainz (person/album), Open Library (book), Wikipedia/Wikidata (bios, comic series), podcast feeds
search/       - Optional Typesense full-text search: index event handlers + query service (package app.ister.search)
transcoder/   - FFmpeg-based HLS transcoding via Jaffree library
```

**Dependency flow:** `server` → `{api, disk, worker, search, transcoder}` → `core` → `database`.
`core` declares `api project(':database')`, so entities/repositories/enums come along **transitively** — that
is the direction, and it is easy to get backwards: `database` depends on nothing internal, and nothing may
make it depend on `core`. `api` additionally depends on `search` and `transcoder` directly.

Note: `core/` and `database/` both contribute to package `app.ister.core.*` (a split package); entities and
repositories live in `database/`, the `Handle`/`MessageQueue`/status/config infra in `core/`.

## Architecture: Event-Driven via RabbitMQ

All significant work is done asynchronously through RabbitMQ message queues. The central interface is `Handle<T extends MessageData>` (core module) which all event handlers implement.

**Event flow:**
1. Trigger (API call, startup, scan request) → `MessageSender.send()` → RabbitMQ queue
2. `Handle<T>` implementation in `disk/`, `worker/`, or `transcoder/` receives message → processes → may send further events

**Two enums, do not confuse them:**
- `EventType` (in `database/.../enums/EventType.java`) is the logical message type. `Handle.handles()` returns an `EventType`, and `listener()` rejects a message whose `eventType` doesn't match. This is the source of truth for "what kinds of events exist" (31 values).
- `MessageQueue` (in `core/.../MessageQueue.java`) holds the queue **base names** (`app.ister.server.<event>`). `MessageSender` maps an event to its queue.

**Event categories** (see `doc/architecture/diagrams/` and `doc/architecture/en/01-event-system.md` for the per-flow mermaid diagrams and the full handler reference — keep those as the canonical reference; any change to `doc/` must be applied to both the `en/` and `nl/` chapters, CI checks the parity):
- `FILE_SCAN_REQUESTED`, `NEW_DIRECTORIES_SCAN_REQUEST` — disk scanning
- `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `IMAGE_FOUND`, `SUBTITLE_FILE_FOUND`, `NFO_FILE_FOUND` — file-type-specific processing
- `MOVIE_FOUND`, `SHOW_FOUND`, `EPISODE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `TRACK_FOUND`, `BOOK_FOUND`, `CHAPTER_FOUND`, `EPUB_FILE_FOUND`, `PODCAST_FOUND`, `PODCAST_EPISODE_FOUND` — metadata fetching from TMDB (incl. cast credits) / MusicBrainz / Open Library (books) / tag or OPF parsing. TMDB details are fetched **once per configured language** (movie/show/episode handlers loop over `LanguageProperties.tags()`), producing one `MetadataEntity` row per language (`MetadataEntity.language` stored as ISO-639-3). A `PersonEntity` is both a music artist and an actor; TMDB cast members are deduplicated on exact name + birth year. A `CreditEntity` links a person to exactly one of movie/show/episode; the GraphQL `Credit` type exposes those back-references (`movie`/`show`/`episode`, batch-resolved in `CreditController`) so a person's filmography is queryable via `personById { credits { movie/show/episode } }`. **Books** (`LibraryType.BOOK`, structure `Author/Book.epub` + `Author/Book/NNN_Chapter.mp3`): one `BookEntity` per logical book (author = `PersonEntity`), formats are attachments — epubs link via `MediaFileEntity.bookEntity`, audiobook mp3s via `ChapterEntity` (streams over the same audio-only HLS path as tracks). `MediaFileEntity.mediaOverlays` marks EPUB 3 read-aloud epubs, detected from the epub CONTENTS (SMIL in the OPF manifest, parsed by `disk/.../epub/EpubParser`), never from the filename. Epubs are read lazily by the client's epub reader through `GET /epub/{mediaFileId}/resource/{entry}` (Range/ETag support, stream-token or cookie auth); reading position is a `WatchStatusEntity` with `readingLocation` (epubcfi) + `readingProgress`, synced via the `updateReadingProgress` mutation or `POST /reading-progress`. **Podcasts** (`LibraryType.PODCAST`) are the first FEED-based library type: no library directory; `subscribePodcast(feedUrl)` + an hourly `PodcastRefreshScheduler` drive `PODCAST_REFRESH_REQUESTED` → worker `RssFeedParser` (conditional GET, guid dedup, max 500 items) → episode rows; the newest N (`app.ister.worker.podcast.auto-download-count`, default 3) are downloaded to `{cache}/podcasts/` via `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (cache-directory-scoped queue) and then flow through the normal `AUDIO_FILE_FOUND` → HLS pipeline; older episodes download on demand (`downloadPodcastEpisode` mutation). The daily cache cleanup also expires podcast downloads (`podcast-retention-days`, default 30) unless someone is mid-episode. Directory search uses the free iTunes Search API (`ItunesSearchService`, api module).
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

## Architecture: Live Status & Remote Playback (`core/.../status/`)

Separate from the work queues, every node publishes its state to a **fanout exchange** (`StatusExchangeConfig`)
that each node consumes on its own anonymous queue (`StatusEventListener`), so cluster state converges
everywhere and any node can answer a subscription. Producers: `NodeActivityPublisher` (heartbeat),
`QueueDepthPoller` (RabbitMQ queue depths), `ProcessingActivityAdvice` (an AOP advice reporting which handler
is busy), `RecentFailuresBuffer`, and `PlaybackStatusService` (client heartbeats → `PlaybackSessionRegistry`,
expired by `PlaybackSessionSweeper`).

`ServerStatusBroadcaster` bridges the registries to GraphQL websocket subscriptions:
`serverActivity` and `nowPlaying` (`ServerStatusController`) and `playbackCommands(playQueueId)`
(`PlaybackCommandController` — party-mode remote control: PLAY/PAUSE/NEXT/SEEK/SKIP_TO_ITEM/QUEUE_CHANGED).

Two invariants worth knowing before touching this: the activity and now-playing sinks are **replay-latest**
(a new subscriber must get current state at once, and an emit from a RabbitMQ listener thread must never
block), while the command sink is deliberately **best-effort, non-replaying** — a re-subscriber replaying the
last command would re-execute it. Handlers here do **no database access**: RabbitMQ listener threads have no
Hibernate session.

## Scheduled Jobs

Roughly a dozen `@Scheduled` beans; the ones with operational consequences:

- `CacheCleanupScheduler` (disk) + `TmpTranscodeCleanupScheduler` (transcoder) — daily zombie sweep of the
  image cache and transcode tmp dirs, deleting files no DB row references (and expiring podcast downloads).
  **`app.ister.server.cache-cleanup.dry-run` defaults to `true`**, so it only logs until switched off.
- `PreTranscodeScheduler` (every 15 min) and `ContinueWatchingRebuildScheduler` (nightly) — see above.
- `PodcastRefreshScheduler` (hourly), `StreamTokenService` (expiry sweep), `NodeTokenManager` (multi-node
  token refresh).

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
- External metadata endpoints: every base URL the workers call (TMDB api + image host, MusicBrainz,
  Cover Art Archive, Commons, Open Library + covers, Wikidata, the Wikipedia summary template,
  iTunes) is a property with the real service as default — see README "Configuration". Keep new
  external calls behind such a property: the chart's e2e serves them all from one WireMock pod and
  fails on any dead-lettered event, so a hardcoded URL breaks that CI.
- Docker images: `docker-publish.yml` tags every main push with the gradle project version
  (e.g. `1.1.1-SNAPSHOT`, greppable from build.gradle) next to `main`; server and migrations get
  the same tag so downstream pins stay in lockstep. Releases get clean semver tags via release.yml.

Config is bound through `@ConfigurationProperties` classes, not scattered `@Value`s: `LanguageProperties`,
`AppIsterServerConfig` (disk), `WorkerDiskConfig`, `TranscoderDirectoryConfig`/`TranscoderDisksConfig`,
`TypesenseProperties`. Machine-local overrides go in gitignored `*-local.properties` (e.g.
`core/src/main/resources/core-local.properties`); `bootRun` forces `spring.profiles.active=local`.

**Local dev credentials** (docker-compose-local.yml): DB = ister/ister, RabbitMQ = user/password

**GraalVM native image is the production artifact**, which constrains how you write code:
- Never use `@ConditionalOnProperty` for a runtime-toggleable feature — bean conditions are frozen at
  native-image build time, so the bean is baked out of the prod image. Check the flag inside the bean instead
  (see the Typesense note above).
- Reflection/resource hints are hand-maintained per module under `src/main/resources/META-INF/native-image/`.
  A properties file imported via `spring.config.import` needs an entry in its module's `resource-config.json`.
- `hibernate.ddl-auto=validate` in prod: an entity change without a matching Flyway migration fails startup.

## API Surface

- **REST**: controllers under `api/` for movies, shows, episodes, seasons, music (person/album/track), books,
  podcasts, credits, scanner, libraries/directories, play queue, watch status, reading progress, per-user
  ratings (`RatingController`), user settings, stream tokens, server info/status. Errors are mapped centrally
  in `api/.../error/` (`RestExceptionHandler`, `GraphQlExceptionResolver`).
- **GraphQL**: Schema at `api/src/main/resources/graphql/schema.graphqls`; GraphQL IDE enabled in dev.
  Queries/mutations **and** the websocket subscriptions described above.
- **Auth**: OAuth2 JWT via Spring Security Resource Server (Keycloak-compatible OIDC). HLS segment/playlist
  requests can also authenticate via a short-lived `?token=` stream token (`StreamTokenAuthenticationFilter`),
  which is injected into playlist URIs server-side. The client's epub reader uses the same mechanism (plus a
  cookie fallback for browser-loaded epub subresources).

Metadata providers live in `worker/.../events/`: TMDB (film/tv), MusicBrainz (music), Open Library (books),
and Wikipedia/Wikidata (`WikipediaService` — multilingual person bios + portraits). HTTP clients for them are
centralized in `worker/.../http/MetadataRestClients`.

## Testing Notes

- Unit tests use JUnit 5 + Mockito (Mockito runs as a `-javaagent`, wired in the root `build.gradle`)
- `jimfs` (in-memory filesystem) used in disk/file-path tests
- ffmpeg must be on `PATH` — transcoder tests shell out to it, and CI installs it before `./gradlew build`
- Integration tests are **not** a separate source set or task: they run under `test`, are named
  `*IntegrationTest`, and use Testcontainers PostgreSQL. They are annotated
  `@Testcontainers(disabledWithoutDocker = true)`, so **they silently skip when no container runtime is
  reachable** — a green `./gradlew test` does not mean they ran. Locally:
  ```bash
  systemctl --user start podman.socket
  DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock ./gradlew test
  ```
- SonarCloud + Jacoco run in CI (`./gradlew build check jacocoTestReport sonar`); there is no separate linter
  or formatter
