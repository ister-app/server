# Ister server

Ister is a self-hosted media server (in the spirit of Plex/Jellyfin) built with Spring Boot and Java 25.
It scans media libraries (movies, TV shows and music), fetches metadata from TMDB and MusicBrainz,
and streams HLS-transcoded media to clients over REST and GraphQL. Multiple nodes can form a
cluster: one node can transcode media that lives on another node's disks.

## Architecture at a glance

Gradle multi-module project; all significant work flows through RabbitMQ events
(see [EVENT_FLOWS.md](EVENT_FLOWS.md) for per-flow diagrams and [CLAUDE.md](CLAUDE.md) for a
deeper tour):

| Module | Responsibility |
| --- | --- |
| `server` | Spring Boot entry point |
| `core` | Shared infra: event contract (`Handle`), queue names, `MessageSender`, event DTOs |
| `database` | JPA entities, repositories, Flyway migrations |
| `api` | REST controllers + GraphQL schema/resolvers |
| `disk` | Library scanning, file-type event handlers, startup tasks |
| `worker` | Metadata fetching (TMDB, MusicBrainz), analysis jobs |
| `transcoder` | FFmpeg-based HLS transcoding (hardware acceleration optional) |

Failed event handlers are retried with backoff; when retries are exhausted the message is
republished to the `app.ister.server.dead-letter` queue with the exception preserved in headers.

## Run locally for development

Start PostgreSQL and RabbitMQ:

```shell
podman-compose -f docker-compose-local.yml up database rabbitMQ
```

Then run the application:

```shell
./gradlew bootRun
```

Local dev credentials live in `docker-compose-local.yml` (DB `ister`/`ister`,
RabbitMQ `user`/`password`). Machine-specific overrides go in `*-local.properties`
files (gitignored), e.g. `core/src/main/resources/core-local.properties`.

## Configuration

Everything is env-overridable; the most important settings:

| Setting | Env var / property | Notes |
| --- | --- | --- |
| PostgreSQL | `DB_HOST`, `DB_PATH`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | defaults target localhost |
| RabbitMQ | `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | |
| OIDC issuer | `OIDC_URL` | Keycloak-compatible; JWT resource server |
| TMDB | `app.ister.server.TMDB.apikey` | API read access token; metadata is skipped without it |
| Typesense | `TYPESENSE_ENABLED`, `TYPESENSE_HOST`, `TYPESENSE_PORT`, `TYPESENSE_API_KEY` | optional full-text search (GraphQL `search` query); run the `reindexSearch` mutation once after enabling to build the initial index |
| FFmpeg | `FFMPEG_DIR`, `MKVEXTRACT`, `SUBTILE_OCR` | binary locations |
| Cache/tmp | `CACHE_DIR`, `TMP_DIR` | HLS segments and image cache |
| Node identity | `app.ister.server.name`, `app.ister.server.url`, `app.ister.cluster.name` | unique per node |
| Libraries | `app.ister.disk.libraries[n].*`, `app.ister.disk.directories[n].*` | see `disk/src/main/resources/disk.properties` |
| Transcoder | `app.ister.transcoder.hls.*` | hwaccel (`vaapi`/`nvdec`), concurrency, timeouts |
| Pre-transcode | `app.ister.server.pretranscode.next-episode-recent-days` | how recently an episode must have been watched for the next episode to be pre-transcoded (default 150, the max the watch-history query looks back) |

## Multi-node

Every node runs the same application with its own `app.ister.server.name` and the directories it
owns. Transcode queues are directory-scoped (`app.ister.server.transcode_requested.<directory>`),
so transcode work is picked up by the node that holds the source file; produced segments are
pushed to the requesting node via `POST /transcode/upload/{id}/{fileName}`, authenticated with
short-lived node tokens.

## API

- REST + GraphQL (schema: `api/src/main/resources/graphql/schema.graphqls`; GraphiQL enabled in dev).
- Auth: OAuth2 JWT (OIDC). HLS/image requests can also authenticate with a short-lived
  `?token=` stream token, which the server injects into playlist URIs.
- Actuator (health/metrics/prometheus) listens on management port `8081`.

## Testing

```shell
./gradlew test
```

Integration tests (Flyway + native queries against real PostgreSQL, full-application boot with
RabbitMQ, dead-letter flow) use Testcontainers and are skipped when no container runtime is
reachable. To run them locally with rootless podman:

```shell
systemctl --user start podman.socket
DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock ./gradlew test
```

## Create a test video

```shell
ffmpeg -f lavfi -i color=size=1280x720:rate=25:color=yellow -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -map 0 -map 1 -map 2 -metadata:s:v:0 language=deu -metadata:s:a:0 language=nld -t 3 output.mkv
```
