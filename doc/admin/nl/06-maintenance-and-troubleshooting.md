# Onderhoud en probleemoplossing

Ister is ontworpen om voor zichzelf te zorgen: caches worden opgeschoond, de
continue-watching-lijst herstelt zichzelf, en mislukt achtergrondwerk wordt bewaard voor
inspectie. Dit hoofdstuk behandelt de bewegende delen die je moet kennen, wat je moet back-uppen
en de gebruikelijke verdachten wanneer iets er raar uitziet.

## Geplande taken

| Taak | Module | Schema (standaard) | Wat hij doet |
| --- | --- | --- | --- |
| Cache-opschoning | disk | dagelijks, 04:30 | verwijdert cachebestanden waar geen databaserij naar verwijst ("zombies"); laat podcastdownloads ouder dan `podcast-retention-days` (30) verlopen, tenzij iemand middenin een aflevering zit |
| Tmp-transcode-opschoning | transcoder | dagelijks, 04:30 | dezelfde sweep voor de tijdelijke HLS-transcodemap |
| Pre-transcode | worker | elke 15 min | warmt HLS-uitvoer op voor wat gebruikers waarschijnlijk als volgende afspelen |
| Continue-watching-rebuild | worker | 's nachts, 03:30 | herberekent de continue-watching-lijst van elke gebruiker vanaf nul; verwijdert items waarvan de media weg is |
| Podcast-verversing | worker | elk uur | haalt elke geabonneerde feed op, zet downloads van nieuwe afleveringen in de queue |
| Token-sweeps | core/transcoder | continu / 12 u | laten streamtokens en afspeelsessies verlopen; verversen node-tokens |

**Belangrijk:** de cache-opschoning wordt geleverd met
**`app.ister.server.cache-cleanup.dry-run=true`** — standaard *logt* hij alleen wat hij zou
verwijderen. Draai een deploy of twee, controleer of de logregels er verstandig uitzien, en zet
dan `CACHE_CLEANUP_DRY_RUN=false` om daadwerkelijk schijfruimte terug te winnen. Hij komt nooit
aan bestanden jonger dan `CACHE_CLEANUP_MIN_AGE` (24u), en nooit aan je media.

## Back-up

**PostgreSQL is de enige bron van waarheid** — het is het enige dat je moet back-uppen (plus je
mediabestanden, die sowieso van jou zijn; de server wijzigt ze nooit). Al het andere is opnieuw
op te bouwen:

| Data | Waar | Herstel |
| --- | --- | --- |
| Afbeeldingscache, podcastdownloads | `CACHE_DIR` | re-scan / `analyzeLibrary`; podcasts worden opnieuw gedownload |
| HLS-segmenten | `TMP_DIR` | worden op verzoek opnieuw getranscodeerd |
| Typesense-index | Typesense-volume | één `reindexSearch`-mutation |
| RabbitMQ-queues | broker | vluchtig werk; een verloren bericht vertraagt hooguit metadata tot de volgende scan |

Dus: `pg_dump` op een schema, en doe geen moeite om de caches te back-uppen.

## Monitoring

- **Actuator** op poort 8081: `/actuator/health` voor probes, `/actuator/prometheus` om te
  scrapen.
- **Dead-letter-queue** — mislukte achtergrondevents worden met backoff opnieuw geprobeerd en
  belanden daarna in de RabbitMQ-queue **`app.ister.server.dead-letter`**, met de exception
  bewaard in de berichtheaders. Houd de diepte in de gaten (RabbitMQ-management-UI op 15672, of
  Prometheus); een groeiende dead-letter-queue is het vroegste teken dat scannen of het ophalen
  van metadata faalt.
- **Live activiteit** — de GraphQL-subscription `serverActivity` (zichtbaar op de
  activiteitenpagina van de client) toont waar elke node op dit moment mee bezig is.

## Probleemoplossing

**Geen metadata na een scan (kale bestandsnamen, geen posters)** — vrijwel altijd een
ontbrekende of verkeerde TMDB-key (`app.ister.server.TMDB.apikey`, een *API read access token*):
zonder die wordt het ophalen van metadata overgeslagen. Stel hem in en draai dan
`analyzeLibrary` om aan te vullen. Controleer ook de dead-letter-queue op rate-limit- of
netwerkfouten.

**Afspelen start nooit / geen transcode** — de server kan FFmpeg niet draaien. Controleer of
`FFMPEG_DIR` naar een map wijst met `ffmpeg` en `ffprobe` (in de officiële image staan ze in
`/usr/bin`). Probeer bij problemen met hardwareversnelling eerst `HLS_HWACCEL=none` om de
GPU-opstelling (device-mapping, `render`-groep) van de pipeline te isoleren.

**Item ontbreekt in (of blijft hangen in) continue watching** — de lijst is voorberekend; als
een client voortgang via een ongebruikelijk pad heeft weggeschreven, kan hij achterlopen. De
nachtelijke rebuild (03:30) repareert dit; hij draait ook eenmalig bij het opstarten wanneer de
tabel leeg is.

**Zoeken geeft niets terug** — Typesense staat uit, is onbereikbaar, of is na het inschakelen
nooit geherindexeerd. Zie [Zoeken](05-search-typesense.md).

**Schijf loopt vol** — controleer of de cache-opschoning nog in dry-run staat (zie hierboven),
en kijk naar de grootte van `CACHE_DIR`/`TMP_DIR` in verhouding tot podcastretentie en
pre-transcode-activiteit.

**Nieuwe bestanden verschijnen niet** — er is geen filesystem-watcher; draai `scanLibrary`.
Worden bestanden wel gevonden maar verkeerd geclassificeerd, vergelijk hun paden dan met
[de verwachte indeling](03-libraries-and-media-layout.md).

Voor een dieper begrip van elk van deze subsystemen begin je bij het
[architectuuroverzicht](../../architecture/nl/00-overview.md).
