---
description: Volledige configuratiereferentie voor de Ister-mediaserver, van database en OIDC tot TMDB-metadata, Typesense-zoeken, transcoding en cache-instellingen.
---

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
| Connectie-timeout (ms) | `DB_CONNECTION_TIMEOUT` | `10000` | hoe lang een thread op een poolverbinding wacht voordat het faalt |
| RabbitMQ | `SPRING_RABBITMQ_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | `localhost`, `5672`, `user`/`password` | poort valt terug op de RabbitMQ-standaard 5672 |
| OIDC-issuer | `OIDC_URL` | `http://keycloak:8060/realms/Ister` | Keycloak-compatibel; de standaard matcht de meegeleverde dev-Keycloak. Productie: bijv. `https://keycloak.example.com/realms/Home` |

## Identiteit en paden

| Instelling | Env var | Standaard | Opmerkingen |
| --- | --- | --- | --- |
| `app.ister.server.name` | `APP_ISTER_SERVER_NAME` | `Test server` | moet uniek zijn per node |
| `app.ister.server.url` | `APP_ISTER_SERVER_URL` | `http://localhost:8080` | hoe clients **én andere nodes** deze node bereiken |
| `app.ister.cluster.name` | `APP_ISTER_CLUSTER_NAME` | de servernaam | dezelfde waarde op elke node van een cluster |
| Cachemap | `CACHE_DIR` | `/cache/` | afbeeldingen, podcastdownloads |
| Tijdelijke map | `TMP_DIR` | `/tmp/ister/` | HLS-transcode-uitvoer |
| `app.ister.server.websocket.allowed-origins` | `APP_ISTER_SERVER_WEBSOCKET_ALLOWED_ORIGINS` | leeg | extra origins die de GraphQL-websocket-handshake accepteert. De publieke host uit `app.ister.server.url` is automatisch toegestaan (beide schema's, elke poort), dus dit is alleen nodig als de server onder een tweede naam wordt aangeboden. `*` staat elke origin toe. |

## Metadata en talen

| Instelling | Env var | Standaard | Opmerkingen |
| --- | --- | --- | --- |
| `app.ister.server.TMDB.apikey` | `APP_ISTER_SERVER_TMDB_APIKEY` | niet gezet | TMDB **API read access token**. Zonder deze wordt het ophalen van film-/seriemetadata overgeslagen — je krijgt kale bestandsnamen. |
| `app.ister.server.TMDB.max-requests-per-second` | | `30` | blijft onder TMDB's limiet van ~40 rps |
| `app.ister.worker.tmdb.certification-country` | | `US` | ISO 3166-1-land waarvan de keuring/leeftijdsclassificatie (bv. `16`, `PG-13`, `TV-MA`) bij films en shows wordt opgeslagen; valt terug op US en daarna op een willekeurig land dat er een heeft. |
| `app.ister.languages` | `ISTER_LANGUAGES` | `en,nl` | kommagescheiden ISO-639-1-tags; de eerste = primair. Bepaalt in welke talen metadata wordt opgehaald **én** welke talen de zoekindex krijgt. Wijzigen vereist een re-scan plus `rebuildSearchIndex` — zie [Zoeken](06-search-typesense.md). |
| `app.ister.worker.musicbrainz.min-request-interval-millis` | | `1100` | tussenruimte tussen MusicBrainz-aanroepen; blijft net boven hun 1-verzoek-per-seconde-beleid — alleen verlagen tegen je eigen mirror |

## Zoeken (Typesense)

| Env var | Standaard |
| --- | --- |
| `TYPESENSE_ENABLED` | `false` |
| `TYPESENSE_HOST` / `TYPESENSE_PORT` / `TYPESENSE_PROTOCOL` | `localhost` / `8108` / `http` |
| `TYPESENSE_API_KEY` / `TYPESENSE_COLLECTION` | leeg / `media` |

Zie [Zoeken](06-search-typesense.md) voor de procedure om in te schakelen en te herindexeren.

## Transcoding

| Instelling | Env var | Standaard | Opmerkingen |
| --- | --- | --- | --- |
| FFmpeg-map | `FFMPEG_DIR` | `/usr/bin` | map met `ffmpeg`/`ffprobe` |
| mkvextract / subtile-ocr | `MKVEXTRACT` / `SUBTILE_OCR` | `/usr/bin/...` | extractie en OCR van beeldondertitels |
| `app.ister.server.subtitle-ocr-default-language` | | `eng` | OCR-taal die wordt aangenomen voor ingebedde beeldondertitels zonder taaltag; leeg schakelt OCR voor taggeloze streams uit |
| `app.ister.transcoder.hls.hwaccel` | `HLS_HWACCEL` | `none` | `vaapi` (Intel/AMD) of `nvdec` (NVIDIA); het compose-bestand toont de benodigde device-mappings |
| `app.ister.transcoder.hls.hwaccel-device` | `HLS_HWACCEL_DEVICE` | `/dev/dri/renderD128` | alleen VAAPI |
| `app.ister.transcoder.hls.max-concurrent-files` | `HLS_MAX_CONCURRENT_FILES` | `2` | gelijktijdig getranscodeerde bestanden; pre-transcoding deelt dit budget |

### Geavanceerde transcoding

Zelden aan te raken — de standaardwaarden passen bij de meeste opstellingen. Deze hebben geen eigen
korte env var (gebruik relaxed binding, bijv. `APP_ISTER_TRANSCODER_HLS_MAX_BACKGROUND_FILES`). De
mechaniek erachter staat in de architectuurgids, [Transcoding](../../architecture/nl/04-transcoding.md).

| Property | Standaard | Opmerkingen |
| --- | --- | --- |
| `app.ister.transcoder.hls.max-concurrent-passes` | `4` | gelijktijdig lopende FFmpeg-passes (threadpoolgrootte) |
| `app.ister.transcoder.hls.max-background-files` | `1` | bestanden die een achtergrond-pre-transcode mag vasthouden, onder het interactieve budget |
| `app.ister.transcoder.hls.max-background-passes` | `2` | passes die een achtergrond-pre-transcode mag vasthouden |
| `app.ister.transcoder.hls.background-nice` | `10` | `nice`-waarde voor achtergrondpasses, zodat interactief afspelen de CPU wint |
| `app.ister.transcoder.hls.nice-path` | `/usr/bin/nice` | pad naar de `nice`-binary voor bovenstaande |
| `app.ister.transcoder.hls.cache-retention-hours` | `2` | een transcode-cachemap wordt pas verwijderd na zo lang onaangeraakt (en voorbij zijn keep-until) |
| `app.ister.transcoder.hls.segment-stability-ms` | `200` | een segmentbestand moet zo lang ongewijzigd zijn voordat het als klaar telt |
| `app.ister.transcoder.hls.pass-timeout-multiplier` | `4` | pass-timeout = mediaduur × dit |
| `app.ister.transcoder.hls.pass-timeout-min-seconds` | `1800` | …maar nooit onder deze ondergrens |
| `app.ister.transcoder.hls.pass-stall-timeout-seconds` | `60` | breek een pass af die zo lang geen nieuw segment schrijft |
| `app.ister.transcoder.hls.upload-drain-timeout-ms` | `300000` | multi-node: hoe lang na afloop van een pass segmenten naar de vragende node geüpload blijven worden |
| `app.ister.server.hls.segment-timeout-ms` | `60000` | hoe lang een HTTP-`.ts`-verzoek op dat segment van de encoder wacht |
| `app.ister.server.hls.master-playlist-timeout-ms` | `120000` | hoe lang een `master.m3u8`-verzoek op het genereren van de playlists wacht |

## Pre-transcoding en prefetch

Twee achtergrondmechanismen warmen de transcode-cache op zodat afspelen direct start.
Pre-transcoding werkt vanuit de continue-watching-lijst; prefetch reageert op wat een play queue nú
doet. Zie de architectuurgids, [Transcoding](../../architecture/nl/04-transcoding.md).

| Property | Standaard | Opmerkingen |
| --- | --- | --- |
| `app.ister.worker.pretranscode.enabled` | `true` | runtime gecheckt: `false` stopt de 15-minuten-pre-transcode-planning volledig |
| `app.ister.server.pretranscode.keep-minutes` | `30` | hoe lang een voor-getranscodeerd bestand warm blijft voordat het geveegd mag worden |
| `app.ister.server.prefetch.enabled` | `true` | prefetch het volgende item in een play queue tijdens afspelen |
| `app.ister.server.prefetch.video-threshold-seconds` | `120` | begin de volgende video te prefetchen na zoveel seconden in de huidige |
| `app.ister.server.prefetch.track-threshold-seconds` | `60` | idem, voor audiotracks |
| `app.ister.server.prefetch.track-depth` | `2` | hoeveel komende tracks vooruit te prefetchen |
| `app.ister.server.prefetch.keep-hours` | `24` | hoe lang een geprefetcht bestand warm blijft |

## Continue watching

| Env var | Standaard | Opmerkingen |
| --- | --- | --- |
| `CONTINUE_WATCHING_HISTORY_DAYS` | `150` | hoe ver de continue-watching-lijst terugkijkt; bepaalt ook wat pre-transcoding warm houdt |
| `CONTINUE_WATCHING_REBUILD_CRON` | `0 30 3 * * *` | nachtelijke zelfherstel-rebuild |
| `CONTINUE_WATCHING_REBUILD_ENABLED` | `true` | runtime gecheckt: `false` slaat zowel de nachtelijke rebuild als de eenmalige startup-backfill over die een lege tabel vult |

Gebruik hier bij voorkeur de korte env vars in plaats van de lange property-namen: de
onderliggende properties zijn inconsistent gebonden (`…rebuild.enabled` met een punt, maar
`…rebuild-cron` met een streepje) — de env vars verbergen dat.

## Cache-opschoning en podcasts

| Instelling | Standaard | Opmerkingen |
| --- | --- | --- |
| `CACHE_CLEANUP_ENABLED` / `CACHE_CLEANUP_CRON` | `true` / `0 30 4 * * *` | dagelijkse zombie-sweep van cache- en tmp-mappen |
| `CACHE_CLEANUP_DRY_RUN` | **`true`** | hij **logt** alleen totdat je dit op `false` zet — zie [Onderhoud](07-maintenance-and-troubleshooting.md) |
| `CACHE_CLEANUP_MIN_AGE` | `24h` | verwijdert nooit bestanden jonger dan dit |
| `app.ister.server.cache-cleanup.podcast-retention-days` | `30` | gedownloade podcastafleveringen verlopen hierna, tenzij iemand middenin een aflevering zit |
| `app.ister.worker.podcast.auto-download-count` | `3` | nieuwste afleveringen die per feed automatisch worden gedownload |
| `app.ister.worker.podcast.refresh-cron` | `0 10 * * * *` | feeds elk uur verversen |
| `app.ister.worker.podcast.refresh-min-interval-minutes` | `30` | een feed wordt binnen dit venster niet opnieuw opgehaald, ook al vuurt de cron |

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
wordt — volledig behandeld in [Libraries en media-indeling](04-libraries-and-media-layout.md).
Aparte transcoder-nodes krijgen schijven toegewezen met `app.ister.transcoder.disks[n].name` — zie
[Multi-node](05-multi-node.md).

## Health, metrics en overige interne knoppen

De Spring Actuator (`/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`) draait op een
eigen poort, zodat die buiten de publieke API blijft. Die poortscheiding is de **enige**
bescherming — de actuator-endpoints zelf zijn niet-geauthenticeerd — dus publiceer of proxy poort
8081 nooit. Zie [Installatie](02-installation.md#health-metrics-logs).

| Property | Standaard | Opmerkingen |
| --- | --- | --- |
| `management.server.port` | `8081` | poort voor de Actuator-endpoints |
| `management.endpoints.web.exposure.include` | `health,metrics,prometheus` | welke Actuator-endpoints beschikbaar zijn |
| `app.ister.server.blur-hash.chunk-size` | `500` | afbeeldingen per chunk tijdens de BlurHash-sweep (houdt een chunk onder de RabbitMQ-consumer-timeout) |
| `app.ister.server.segment-detect.chunk-size` | `4` | afleveringen die per intro-/outro-detectiebericht worden gefingerprint; houd een chunk ruim onder de RabbitMQ-consumer-timeout |
| `app.ister.server.crop-detect-backfill` | `true` | ontsnappingsluik: `false` slaat de eenmalige crop-detectie-heranalyse van elk bestaand bestand bij de eerste scan na de upgrade over — zie [Onderhoud](07-maintenance-and-troubleshooting.md#eenmalige-upgrade-stappen) |
| `app.ister.server.segment-detect-backfill` | `true` | zelfde ontsnappingsluik voor het eenmalige intro-/outro-fingerprinten van elke bestaande aflevering |

## Lokale overrides (ontwikkeling)

Draai je vanuit de broncode, dan gaan machinespecifieke instellingen in gegitignorede
`*-local.properties`-bestanden naast het properties-bestand van de module (bijv.
`core/src/main/resources/core-local.properties`); `./gradlew bootRun` activeert automatisch het
`local`-profiel. Containerdeployments gebruiken in plaats daarvan omgevingsvariabelen.
