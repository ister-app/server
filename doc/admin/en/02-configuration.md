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
| Connection timeout (ms) | `DB_CONNECTION_TIMEOUT` | `10000` | how long a thread waits for a pooled connection before failing |
| RabbitMQ | `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | `localhost`, `5672`, `user`/`password` | port defaults to the RabbitMQ default 5672 |
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

### Advanced transcoding

Rarely touched — the defaults suit most setups. These have no dedicated short env var (use relaxed
binding, e.g. `APP_ISTER_TRANSCODER_HLS_MAX_BACKGROUND_FILES`). The mechanics behind them are in the
architecture guide, [Transcoding](../../architecture/en/04-transcoding.md).

| Property | Default | Notes |
| --- | --- | --- |
| `app.ister.transcoder.hls.max-concurrent-passes` | `4` | FFmpeg passes running at once (thread pool size) |
| `app.ister.transcoder.hls.max-background-files` | `1` | files a background pre-transcode may hold, kept below the interactive budget |
| `app.ister.transcoder.hls.max-background-passes` | `2` | passes a background pre-transcode may hold |
| `app.ister.transcoder.hls.background-nice` | `10` | `nice` value for background passes so interactive playback wins the CPU |
| `app.ister.transcoder.hls.nice-path` | `/usr/bin/nice` | path to the `nice` binary used for the above |
| `app.ister.transcoder.hls.cache-retention-hours` | `2` | a transcode cache dir is removed only after being untouched this long (and past its keep-until) |
| `app.ister.transcoder.hls.segment-stability-ms` | `200` | a segment file must be unchanged this long before it counts as finished |
| `app.ister.transcoder.hls.pass-timeout-multiplier` | `4` | pass timeout = media duration × this |
| `app.ister.transcoder.hls.pass-timeout-min-seconds` | `1800` | …but never below this floor |
| `app.ister.transcoder.hls.pass-stall-timeout-seconds` | `60` | abort a pass that writes no new segment for this long |
| `app.ister.transcoder.hls.upload-drain-timeout-ms` | `300000` | multi-node: how long to keep uploading segments to the requesting node after a pass ends |
| `app.ister.server.hls.segment-timeout-ms` | `60000` | how long an HTTP `.ts` request waits for the encoder to produce that segment |
| `app.ister.server.hls.master-playlist-timeout-ms` | `120000` | how long a `master.m3u8` request waits for playlists to be generated |

## Pre-transcoding and prefetch

Two background mechanisms warm the transcode cache so playback starts instantly. Pre-transcoding
works from the continue-watching list; prefetch reacts to what a play queue is doing right now. See
the architecture guide, [Transcoding](../../architecture/en/04-transcoding.md).

| Property | Default | Notes |
| --- | --- | --- |
| `app.ister.server.pretranscode.keep-minutes` | `30` | how long a pre-transcoded file is kept warm before it may be swept |
| `app.ister.server.prefetch.enabled` | `true` | prefetch the next item in a play queue during playback |
| `app.ister.server.prefetch.video-threshold-seconds` | `120` | start prefetching the next video once this many seconds into the current one |
| `app.ister.server.prefetch.track-threshold-seconds` | `60` | same, for audio tracks |
| `app.ister.server.prefetch.track-depth` | `2` | how many upcoming tracks to prefetch |
| `app.ister.server.prefetch.keep-hours` | `24` | how long a prefetched file is kept warm |

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
| `app.ister.worker.podcast.refresh-min-interval-minutes` | `30` | a feed is not re-fetched again within this window, even if the cron fires |

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
covered in full in [Libraries and media layout](03-libraries-and-media-layout.md). Dedicated
transcoder nodes are assigned disks with `app.ister.transcoder.disks[n].name` — see
[Multi-node](04-multi-node.md).

## Health, metrics, and other internals

The Spring Actuator (`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`) runs on its
own port so it stays off the public API. See [Installation](01-installation.md#health-metrics-logs).

| Property | Default | Notes |
| --- | --- | --- |
| `management.server.port` | `8081` | port for the Actuator endpoints |
| `management.endpoints.web.exposure.include` | `health,metrics,prometheus` | which Actuator endpoints are exposed |
| `app.ister.server.blur-hash.chunk-size` | `500` | images processed per chunk during the BlurHash sweep (keeps a chunk under the RabbitMQ consumer timeout) |

## Local overrides (development)

When running from source, machine-specific settings go in gitignored `*-local.properties` files
next to the module's properties file (e.g. `core/src/main/resources/core-local.properties`);
`./gradlew bootRun` activates the `local` profile automatically. Container deployments should use
environment variables instead.
