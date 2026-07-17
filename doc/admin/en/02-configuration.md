# Configuration

Everything is a Spring property and every property is overridable via environment variables
(relaxed binding: `app.ister.server.name` ⇒ `APP_ISTER_SERVER_NAME`). Many settings also have a
dedicated short env var, listed below. Defaults are sensible for a single-node home setup.

## Core services

| Setting | Env var | Default | Notes |
| --- | --- | --- | --- |
| Database host / port / name | `DB_HOST` / `DB_PATH` / `DB_NAME` | `localhost` / `5432` / `ister` | PostgreSQL |
| Database credentials | `DB_USER` / `DB_PASSWORD` | `ister` / `ister` | change in production |
| Connection pool | `DB_POOL_SIZE` | `20` | playback fans out to many concurrent queries |
| RabbitMQ | `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | `localhost`, `user`/`password` | |
| OIDC issuer | `OIDC_URL` | — | Keycloak-compatible; e.g. `https://keycloak.example.com/realms/Home` |

## Identity and paths

| Setting | Env var | Default | Notes |
| --- | --- | --- | --- |
| `app.ister.server.name` | `APP_ISTER_SERVER_NAME` | `Test server` | must be unique per node |
| `app.ister.server.url` | `APP_ISTER_SERVER_URL` | `http://localhost:8080` | how clients **and other nodes** reach this node |
| `app.ister.cluster.name` | `APP_ISTER_CLUSTER_NAME` | the server name | same value on every node of a cluster |
| Cache directory | `CACHE_DIR` | `/cache/` | images, podcast downloads |
| Temp directory | `TMP_DIR` | `/tmp/ister/` | HLS transcode output |

## Metadata and languages

| Setting | Env var | Default | Notes |
| --- | --- | --- | --- |
| `app.ister.server.TMDB.apikey` | `APP_ISTER_SERVER_TMDB_APIKEY` | unset | TMDB **API read access token**. Without it, movie/show metadata fetching is skipped — you get bare filenames. |
| `app.ister.server.TMDB.max-requests-per-second` | | `30` | stays under TMDB's ~40 rps limit |
| `app.ister.languages` | `ISTER_LANGUAGES` | `en,nl` | comma-separated ISO-639-1 tags; first = primary. Drives which languages metadata is fetched in **and** which languages search indexes. Changing it requires a re-scan plus `reindexSearch` — see [Search](05-search-typesense.md). |

## Search (Typesense)

| Env var | Default |
| --- | --- |
| `TYPESENSE_ENABLED` | `false` |
| `TYPESENSE_HOST` / `TYPESENSE_PORT` / `TYPESENSE_PROTOCOL` | `localhost` / `8108` / `http` |
| `TYPESENSE_API_KEY` / `TYPESENSE_COLLECTION` | empty / `media` |

See [Search](05-search-typesense.md) for the enable/reindex procedure.

## Transcoding

| Setting | Env var | Default | Notes |
| --- | --- | --- | --- |
| FFmpeg directory | `FFMPEG_DIR` | `/usr/bin` | directory holding `ffmpeg`/`ffprobe` |
| mkvextract / subtile-ocr | `MKVEXTRACT` / `SUBTILE_OCR` | `/usr/bin/...` | image-subtitle extraction and OCR |
| `app.ister.transcoder.hls.hwaccel` | `HLS_HWACCEL` | `none` | `vaapi` (Intel/AMD) or `nvdec` (NVIDIA); the compose file shows the required device mappings |
| `app.ister.transcoder.hls.hwaccel-device` | `HLS_HWACCEL_DEVICE` | `/dev/dri/renderD128` | VAAPI only |
| `app.ister.transcoder.hls.max-concurrent-files` | `HLS_MAX_CONCURRENT_FILES` | `2` | files transcoded simultaneously; pre-transcoding shares this budget |

## Continue watching

| Env var | Default | Notes |
| --- | --- | --- |
| `CONTINUE_WATCHING_HISTORY_DAYS` | `150` | how far back the continue-watching list looks; also drives what pre-transcoding keeps warm |
| `CONTINUE_WATCHING_REBUILD_CRON` | `0 30 3 * * *` | nightly self-heal rebuild |
| `CONTINUE_WATCHING_REBUILD_ENABLED` | `true` | |

## Cache cleanup and podcasts

| Setting | Default | Notes |
| --- | --- | --- |
| `CACHE_CLEANUP_ENABLED` / `CACHE_CLEANUP_CRON` | `true` / `0 30 4 * * *` | daily zombie sweep of cache and tmp dirs |
| `CACHE_CLEANUP_DRY_RUN` | **`true`** | it only **logs** until you set this to `false` — see [Maintenance](06-maintenance-and-troubleshooting.md) |
| `CACHE_CLEANUP_MIN_AGE` | `24h` | never deletes files younger than this |
| `app.ister.server.cache-cleanup.podcast-retention-days` | `30` | downloaded podcast episodes expire after this, unless someone is mid-episode |
| `app.ister.worker.podcast.auto-download-count` | `3` | newest episodes auto-downloaded per feed |
| `app.ister.worker.podcast.refresh-cron` | `0 10 * * * *` | hourly feed refresh |

## External metadata endpoints

Every external service the server calls is a property whose default is the real service, so you
normally set none of these. They exist so a deployment can route through a proxy or mock — the
chart's CI points them all at one WireMock pod:

`spring.cloud.openfeign.client.config.tmdb.url`, `app.ister.worker.tmdb.image-base`,
`app.ister.worker.musicbrainz.base` / `.coverart-release-base` / `.coverart-release-group-base` /
`.commons-filepath-base`, `app.ister.worker.openlibrary.base` / `.covers-base` /
`.author-photo-base`, `app.ister.worker.wikidata.entity-base` / `.api-base`,
`app.ister.worker.wikipedia.summary-template`, `app.ister.api.podcast.itunes-base`.

## Libraries and directories

`app.ister.disk.libraries[n].*` and `app.ister.disk.directories[n].*` define what gets scanned —
covered in full in [Libraries and media layout](03-libraries-and-media-layout.md).

## Local overrides (development)

When running from source, machine-specific settings go in gitignored `*-local.properties` files
next to the module's properties file (e.g. `core/src/main/resources/core-local.properties`);
`./gradlew bootRun` activates the `local` profile automatically. Container deployments should use
environment variables instead.
