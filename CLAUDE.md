# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Ister Server** is a media server application (similar to Plex/Jellyfin) built with Spring Boot 4.0.6 and Java 25. It scans media libraries (movies, TV shows, **and music**), fetches metadata from TMDB, and streams HLS-transcoded media to clients via REST and GraphQL APIs. It supports a **multi-node** deployment where one node transcodes media owned by another.

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
transcoder/   - FFmpeg-based HLS transcoding via Jaffree library
```

**Dependency flow:** `server` → `{api, disk, worker, transcoder}` → `database` → `core` (no other internal deps).
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
- `MOVIE_FOUND`, `SHOW_FOUND`, `EPISODE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `TRACK_FOUND` — metadata fetching from TMDB (incl. cast credits) / MusicBrainz / tag parsing. A `PersonEntity` is both a music artist and an actor; TMDB cast members are deduplicated on exact name + birth year.
- `ANALYZE_DATA`, `ANALYZE_LIBRARY_REQUEST`, `UPDATE_IMAGES_REQUESTED` — analysis & image refresh
- `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED`, `PRE_TRANSCODE_RECENTLY_WATCHED` — HLS transcoding (see below)

**Directory/node-scoped queues:** transcode queues are not global. `TranscoderQueueNamingConfig` appends the directory (or disk) name, e.g. `app.ister.server.transcode_requested.<directoryName>`. Each node listens only on the queues for the directories it owns, so transcode work routes to the node that holds the source file.

## Architecture: HLS Transcoding (transcoder module)

Streaming is HLS. `HlsService` + `HlsTranscodeService` coordinate playlist building and FFmpeg.

- **Playlists are generated up-front; segments are produced lazily.** A `GET .../master.m3u8` cache-miss sends `TRANSCODE_REQUESTED`; `HandleTranscodeRequested` → `generateAllPlaylists` writes master + per-stream `.m3u8` to `tmpDir/{mediaFileId}/`, and the HTTP thread polls until they exist.
- **One continuous FFmpeg pass per quality level**, not one process per segment. Each pass uses `-f segment -segment_times` so the encoder never resets PTS (avoids A/V drift). The first `.ts` request for a quality sends `TRANSCODE_PASS_REQUESTED`; segment requests poll the cache dir until the pass has written (and closed) that segment. Because passes encode sequentially from t=0, a forward seek waits for the encoder to catch up.
- **Concurrency is bounded:** `transcodeExecutor` (fixed 4 threads) + `concurrentFileSlots` semaphore (`max-concurrent-files`, default 2). A pass holds a thread for the whole file duration. Pre-transcoding (`startAllPasses`, triggered by `PRE_TRANSCODE_RECENTLY_WATCHED`) competes for the same pool — beware of starving interactive playback.
- **Multi-node:** for a remote file the transcode runs on the owner node; a watcher thread uploads each stable segment to the requesting node via `POST /transcode/upload/{id}/{fileName}` (`FileController`). `resolveInputPath` feeds FFmpeg a tokenized `…/download` URL when the file is remote.

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
