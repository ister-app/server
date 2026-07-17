# Ister server

Ister is a self-hosted media server (in the spirit of Plex/Jellyfin) built with Spring Boot and Java 25.
It scans media libraries (movies, TV shows and music), fetches metadata from TMDB and MusicBrainz,
and streams HLS-transcoded media to clients over REST and GraphQL. Multiple nodes can form a
cluster: one node can transcode media that lives on another node's disks.

## Architecture at a glance

Gradle multi-module project; all significant work flows through RabbitMQ events
(see the [documentation](doc/README.md) — per-flow diagrams and developer chapters under
[doc/architecture/](doc/architecture/en/00-overview.md), an operator guide under
[doc/admin/](doc/admin/en/00-introduction.md)):

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
| Languages | `ISTER_LANGUAGES` (`app.ister.languages`) | app-wide list of ISO-639-1 tags (default `en,nl`); drives both which languages TMDB metadata is fetched in and which languages search indexes — see [Languages](#languages) |
| Typesense | `TYPESENSE_ENABLED`, `TYPESENSE_HOST`, `TYPESENSE_PORT`, `TYPESENSE_API_KEY` | optional full-text search (GraphQL `search` query); run the `reindexSearch` mutation once after enabling to build the initial index |
| FFmpeg | `FFMPEG_DIR`, `MKVEXTRACT`, `SUBTILE_OCR` | binary locations |
| Cache/tmp | `CACHE_DIR`, `TMP_DIR` | HLS segments and image cache |
| Node identity | `app.ister.server.name`, `app.ister.server.url`, `app.ister.cluster.name` | unique per node |
| Libraries | `app.ister.disk.libraries[n].*`, `app.ister.disk.directories[n].*` | see `disk/src/main/resources/disk.properties` |
| Transcoder | `app.ister.transcoder.hls.*` | hwaccel (`vaapi`/`nvdec`), concurrency, timeouts |
| Continue watching | `CONTINUE_WATCHING_HISTORY_DAYS`, `CONTINUE_WATCHING_REBUILD_CRON` | how far back the continue-watching list looks (default 150 days), and when the nightly rebuild of that list runs. It also drives what pre-transcoding keeps warm. |
| External metadata endpoints | `spring.cloud.openfeign.client.config.tmdb.url`, `app.ister.worker.tmdb.image-base`, `app.ister.worker.musicbrainz.base` / `.coverart-release-base` / `.coverart-release-group-base` / `.commons-filepath-base`, `app.ister.worker.openlibrary.base` / `.covers-base` / `.author-photo-base`, `app.ister.worker.wikidata.entity-base` / `.api-base`, `app.ister.worker.wikipedia.summary-template`, `app.ister.api.podcast.itunes-base` | every external source the workers call; defaults are the real services. The chart's CI points them all at one WireMock pod (`chart/ci/mock-external.yaml`) so e2e runs offline and deterministically |

## Multi-node

Every node runs the same application with its own `app.ister.server.name` and the directories it
owns. Transcode queues are directory-scoped (`app.ister.server.transcode_requested.<directory>`),
so transcode work is picked up by the node that holds the source file; produced segments are
pushed to the requesting node via `POST /transcode/upload/{id}/{fileName}`, authenticated with
short-lived node tokens.

## Languages

A single app-wide list of supported languages drives everything multilingual. It is configured as
comma-separated **ISO-639-1 / BCP-47 tags** and defaults to `en,nl`:

```properties
app.ister.languages=${ISTER_LANGUAGES:en,nl}   # in core.properties
```

The list is exposed as `LanguageProperties` (`core/.../config/LanguageProperties.java`) and consumed
by two subsystems:

- **Metadata fetching (worker).** For every configured tag, TMDB details are fetched in that
  language, producing one `MetadataEntity` row per language per media item (title, description,
  genre, release date). The first tag is the primary/fallback language.
- **Search (Typesense).** The collection schema and the search query are generated from the same
  list: each language gets its own `title_<tag>` / `description_<tag>` / `genre_<tag>` fields, each
  carrying the matching Typesense `locale` so tokenization is language-aware. A search queries across
  all language fields at once.

Two code systems are in play and bridged automatically: TMDB and the Typesense `locale`/field suffix
use the ISO-639-1 tag (`en`), while `MetadataEntity.language` is stored as ISO-639-3 (`eng`, via
`Locale.forLanguageTag(tag).getISO3Language()`).

### Adding or changing a language

1. Update the list, e.g. `ISTER_LANGUAGES=en,nl,de`, and restart.
2. **Re-scan / re-fetch metadata** so the new language's `MetadataEntity` rows are created from TMDB
   (the index can only surface metadata that exists in PostgreSQL).
3. Run the `reindexSearch` GraphQL mutation once. The Typesense collection schema is fixed at
   creation time, so a new language needs a fresh collection; `reindexSearch` builds one and swaps
   the alias, keeping search live during the rebuild.

Removing a language and reindexing simply drops its fields from the new collection; the stored
`MetadataEntity` rows are left untouched.

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

Beyond the module tests, the [chart repo](https://github.com/ister-app/chart) runs a full
end-to-end suite against a kind deployment of this server: scanning every library type,
metadata enrichment against mocked external sources, HLS streaming with a real transcode,
epub serving, search and watch status — plus the player repo's Flutter integration tests on
top of the same deployment. See the chart README under "CI".

## Create a test video

```shell
ffmpeg -f lavfi -i color=size=1280x720:rate=25:color=yellow -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 -map 0 -map 1 -map 2 -metadata:s:v:0 language=deu -metadata:s:a:0 language=nld -t 3 output.mkv
```
