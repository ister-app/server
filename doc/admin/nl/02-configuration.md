# Configuratie

Alles is een Spring-property en elke property is te overschrijven via omgevingsvariabelen
(relaxed binding: `app.ister.server.name` ⇒ `APP_ISTER_SERVER_NAME`). Veel instellingen hebben
daarnaast een eigen korte env var, hieronder vermeld. De standaardwaarden zijn verstandig voor
een single-node thuisopstelling.

## Kernservices

| Instelling | Env var | Standaard | Opmerkingen |
| --- | --- | --- | --- |
| Databasehost / poort / naam | `DB_HOST` / `DB_PATH` / `DB_NAME` | `localhost` / `5432` / `ister` | PostgreSQL |
| Database-inloggegevens | `DB_USER` / `DB_PASSWORD` | `ister` / `ister` | wijzig dit in productie |
| Connectionpool | `DB_POOL_SIZE` | `20` | afspelen waaiert uit naar veel gelijktijdige queries |
| RabbitMQ | `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | `localhost`, `user`/`password` | |
| OIDC-issuer | `OIDC_URL` | — | Keycloak-compatibel; bijv. `https://keycloak.example.com/realms/Home` |

## Identiteit en paden

| Instelling | Env var | Standaard | Opmerkingen |
| --- | --- | --- | --- |
| `app.ister.server.name` | `APP_ISTER_SERVER_NAME` | `Test server` | moet uniek zijn per node |
| `app.ister.server.url` | `APP_ISTER_SERVER_URL` | `http://localhost:8080` | hoe clients **én andere nodes** deze node bereiken |
| `app.ister.cluster.name` | `APP_ISTER_CLUSTER_NAME` | de servernaam | dezelfde waarde op elke node van een cluster |
| Cachemap | `CACHE_DIR` | `/cache/` | afbeeldingen, podcastdownloads |
| Tijdelijke map | `TMP_DIR` | `/tmp/ister/` | HLS-transcode-uitvoer |

## Metadata en talen

| Instelling | Env var | Standaard | Opmerkingen |
| --- | --- | --- | --- |
| `app.ister.server.TMDB.apikey` | `APP_ISTER_SERVER_TMDB_APIKEY` | niet gezet | TMDB **API read access token**. Zonder deze wordt het ophalen van film-/seriemetadata overgeslagen — je krijgt kale bestandsnamen. |
| `app.ister.server.TMDB.max-requests-per-second` | | `30` | blijft onder TMDB's limiet van ~40 rps |
| `app.ister.languages` | `ISTER_LANGUAGES` | `en,nl` | kommagescheiden ISO-639-1-tags; de eerste = primair. Bepaalt in welke talen metadata wordt opgehaald **én** welke talen de zoekindex krijgt. Wijzigen vereist een re-scan plus `reindexSearch` — zie [Zoeken](05-search-typesense.md). |

## Zoeken (Typesense)

| Env var | Standaard |
| --- | --- |
| `TYPESENSE_ENABLED` | `false` |
| `TYPESENSE_HOST` / `TYPESENSE_PORT` / `TYPESENSE_PROTOCOL` | `localhost` / `8108` / `http` |
| `TYPESENSE_API_KEY` / `TYPESENSE_COLLECTION` | leeg / `media` |

Zie [Zoeken](05-search-typesense.md) voor de procedure om in te schakelen en te herindexeren.

## Transcoding

| Instelling | Env var | Standaard | Opmerkingen |
| --- | --- | --- | --- |
| FFmpeg-map | `FFMPEG_DIR` | `/usr/bin` | map met `ffmpeg`/`ffprobe` |
| mkvextract / subtile-ocr | `MKVEXTRACT` / `SUBTILE_OCR` | `/usr/bin/...` | extractie en OCR van beeldondertitels |
| `app.ister.transcoder.hls.hwaccel` | `HLS_HWACCEL` | `none` | `vaapi` (Intel/AMD) of `nvdec` (NVIDIA); het compose-bestand toont de benodigde device-mappings |
| `app.ister.transcoder.hls.hwaccel-device` | `HLS_HWACCEL_DEVICE` | `/dev/dri/renderD128` | alleen VAAPI |
| `app.ister.transcoder.hls.max-concurrent-files` | `HLS_MAX_CONCURRENT_FILES` | `2` | gelijktijdig getranscodeerde bestanden; pre-transcoding deelt dit budget |

## Continue watching

| Env var | Standaard | Opmerkingen |
| --- | --- | --- |
| `CONTINUE_WATCHING_HISTORY_DAYS` | `150` | hoe ver de continue-watching-lijst terugkijkt; bepaalt ook wat pre-transcoding warm houdt |
| `CONTINUE_WATCHING_REBUILD_CRON` | `0 30 3 * * *` | nachtelijke zelfherstel-rebuild |
| `CONTINUE_WATCHING_REBUILD_ENABLED` | `true` | |

## Cache-opschoning en podcasts

| Instelling | Standaard | Opmerkingen |
| --- | --- | --- |
| `CACHE_CLEANUP_ENABLED` / `CACHE_CLEANUP_CRON` | `true` / `0 30 4 * * *` | dagelijkse zombie-sweep van cache- en tmp-mappen |
| `CACHE_CLEANUP_DRY_RUN` | **`true`** | hij **logt** alleen totdat je dit op `false` zet — zie [Onderhoud](06-maintenance-and-troubleshooting.md) |
| `CACHE_CLEANUP_MIN_AGE` | `24h` | verwijdert nooit bestanden jonger dan dit |
| `app.ister.server.cache-cleanup.podcast-retention-days` | `30` | gedownloade podcastafleveringen verlopen hierna, tenzij iemand middenin een aflevering zit |
| `app.ister.worker.podcast.auto-download-count` | `3` | nieuwste afleveringen die per feed automatisch worden gedownload |
| `app.ister.worker.podcast.refresh-cron` | `0 10 * * * *` | feeds elk uur verversen |

## Externe metadata-endpoints

Elke externe service die de server aanroept is een property met de echte service als standaard,
dus normaal stel je hier niets van in. Ze bestaan zodat een deployment via een proxy of mock kan
lopen — de CI van de chart wijst ze allemaal naar één WireMock-pod:

`spring.cloud.openfeign.client.config.tmdb.url`, `app.ister.worker.tmdb.image-base`,
`app.ister.worker.musicbrainz.base` / `.coverart-release-base` / `.coverart-release-group-base` /
`.commons-filepath-base`, `app.ister.worker.openlibrary.base` / `.covers-base` /
`.author-photo-base`, `app.ister.worker.wikidata.entity-base` / `.api-base`,
`app.ister.worker.wikipedia.summary-template`, `app.ister.api.podcast.itunes-base`.

## Libraries en directories

`app.ister.disk.libraries[n].*` en `app.ister.disk.directories[n].*` bepalen wat er gescand
wordt — volledig behandeld in [Libraries en media-indeling](03-libraries-and-media-layout.md).

## Lokale overrides (ontwikkeling)

Draai je vanuit de broncode, dan gaan machinespecifieke instellingen in gegitignorede
`*-local.properties`-bestanden naast het properties-bestand van de module (bijv.
`core/src/main/resources/core-local.properties`); `./gradlew bootRun` activeert automatisch het
`local`-profiel. Containerdeployments gebruiken in plaats daarvan omgevingsvariabelen.
