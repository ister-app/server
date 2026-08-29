---
description: "Onderhoudsgids voor de Ister-mediaserver: geplande opschoontaken, back-ups van PostgreSQL, monitoring en de gebruikelijke problemen met hun oplossingen."
---

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
| Transcode-cache-sweep | transcoder | elke 15 min | verwijdert afgeronde HLS-uitvoer ouder dan `cache-retention-hours` (2 u), met respect voor de `keep_until`-markering van elke sessie — dít is de sweep die tussen de dagelijkse runs door daadwerkelijk HLS-ruimte vrijmaakt |
| Streamtoken-verloop | database | elk uur | verwijdert verlopen HLS-/afbeeldings-streamtokens |
| Afspeelsessie-sweep | core | elke 15 s | laat verlopen client-afspeelsessies uit het live register verdwijnen |
| Node-token-verversing | transcoder | elke 12 u | ververst de tokens waarmee nodes zich bij elkaar authenticeren |

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
| Afbeeldingscache, podcastdownloads | `CACHE_DIR` | re-scan / `refreshMetadata`; podcasts worden opnieuw gedownload |
| HLS-segmenten | `TMP_DIR` | worden op verzoek opnieuw getranscodeerd |
| Typesense-index | Typesense-volume | één `rebuildSearchIndex`-mutation |
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
  activiteitenpagina van de client) toont waar elke node op dit moment mee bezig is, **inclusief
  de queue-dieptes** (`queueStats`) — voor een routinematige "loopt de achterstand leeg?"-check
  heb je de RabbitMQ-UI niet nodig.
- Poortbotsing om op te letten: de actuator luistert op **8081**, wat ook een populaire poort is
  om een node op te publiceren (het meegeleverde multi-node-composebestand publiceert node 1 op
  hostpoort 8081). Houd de managementpoort strikt intern — de endpoints zijn niet-geauthenticeerd
  ([Configuratie](03-configuration.md)).

## Eenmalige upgrade-stappen

**Crop-detectie en intro-/outro-detectie (`V37`/`V38`) vullen zich bij bij de eerste scan na de
upgrade.** De eerste `scanLibraries`-run analyseert **elk bestaand videobestand** opnieuw
(zwarte-balken-cropdetectie) en fingerprint **elke aflevering** (intro-/outro-detectie) — veruit
het zwaarste onderdeel van de upgrade op een grote bibliotheek, en het concurreert met gewoon
transcoderen om CPU. Ontsnappingsluiken om het uit te stellen:
`app.ister.server.crop-detect-backfill=false` en
`app.ister.server.segment-detect-backfill=false`; nieuwe bestanden worden hoe dan ook
geanalyseerd.

**Artiesten worden samengevoegd bij de eerste start na migratie `V43`.** Tot dan kon één artiest
meerdere keren bestaan — "ABBA" naast "Abba", en "X feat. Y" als derde artiest die noch X noch Y
kon zien. `V43` herschrijft de `feat.`-namen naar hun primaire artiest (en crediteert de gast op de
betrokken nummers), voegt de dubbelen samen tot één persoon en legt de identiteit vast op de naam
zonder hoofdlettergevoeligheid. Kijkgeschiedenis en ratings blijven behouden: waar twee rijen
botsen, winnen de verste voortgang en de hoogste rating. De migratie is **niet omkeerbaar** — maak
eerst een back-up (zie [Back-up](#back-up)).

Draai daarna één keer de GraphQL-mutation `rebuildSearchIndex`: de samengevoegde personen laten
verouderde documenten in Typesense achter. Featured gasten in bestanden die nooit in een
artiestenrij stonden, komen mee met een gewone hersan.

**Uitgebreide TMDB-metadata (`V45`) vereist een eenmalige backfill.** Genres, community-rating,
speelduur, tagline, keuring, trailer, studio's/netwerken, collectie en keywords worden alleen
opgehaald wanneer de film-/show-/afleveringshandlers draaien; bestaande items blijven dus leeg tot
een verversing. Draai na de upgrade één keer de GraphQL-mutation `refreshMetadata` (mode `MISSING`,
de standaard): die pakt elke film en show op waarvan de verrijkingskolommen nooit gevuld zijn.
Speelduur/stemmen van afleveringen hebben zo'n marker niet — ververs bestaande afleveringen met
`refreshMetadata(mode: FORCE, libraryId: …)` per showbibliotheek (of `refreshShow` per show). De
zoekindex volgt automatisch (geen `rebuildSearchIndex` nodig — de `genre_<tag>`-velden bestaan al
in het collectieschema).

**Scan één keer opnieuw na de artwork-naamfix.** Lokale artwork-bestanden met `folder`, `poster`
of `artist` in de naam werden vroeger stilletjes door de scanner genegeerd; ze worden nu
geaccepteerd (zie [Naamconventies](08-naming-conventions.md)). Draai na de upgrade één keer
`scanLibraries` om bestanden op te pikken die eerdere scans oversloegen.

**Force-verversingen laten verweesde afbeeldingsbestanden achter.** De FORCE-flow en de per-item
`refresh*`-mutations verwijderen afbeeldings-*rijen* en downloaden artwork onder nieuwe namen; de
oude cachebestanden worden opgeruimd door de dagelijkse cache-opschoning — die alleen logt tot
`CACHE_CLEANUP_DRY_RUN=false` (zie hierboven).

## Probleemoplossing

**Geen metadata na een scan (kale bestandsnamen, geen posters)** — vrijwel altijd een
ontbrekende of verkeerde TMDB-key (`app.ister.server.TMDB.apikey`, een *API read access token*):
zonder die wordt het ophalen van metadata overgeslagen. Stel hem in en draai dan
`refreshMetadata` om aan te vullen. Controleer ook de dead-letter-queue op rate-limit- of
netwerkfouten.

**Afspelen start nooit / geen transcode** — de server kan FFmpeg niet draaien. Controleer of
`FFMPEG_DIR` naar een map wijst met `ffmpeg` en `ffprobe` (in de officiële image staan ze in
`/usr/bin`). Probeer bij problemen met hardwareversnelling eerst `HLS_HWACCEL=none` om de
GPU-opstelling (device-mapping, `render`-groep) van de pipeline te isoleren.

**Item ontbreekt in (of blijft hangen in) continue watching** — de lijst is voorberekend; als
een client voortgang via een ongebruikelijk pad heeft weggeschreven, kan hij achterlopen. De
nachtelijke rebuild (03:30) repareert dit; hij draait ook eenmalig bij het opstarten wanneer de
tabel leeg is.

**Zoeken geeft een fout** ("Search is not configured on this server") — `TYPESENSE_ENABLED`
staat op `false`. Staat zoeken wél aan maar is het *leeg*, dan is Typesense onbereikbaar of is er
na het inschakelen nooit geherindexeerd. Zie [Zoeken](06-search-typesense.md).

**Een node weigert te starten met "Directory X name is already used by an other node"** — twee
nodes claimen dezelfde directorynaam. Het opstarten breekt bewust af; hernoem één kant (of
repareer de gekopieerde configuratie) — zie [Multi-node](05-multi-node.md).

**Lokaal artwork verschijnt niet** — de bestandsnaam van de afbeelding moet een van `cover`,
`folder`, `poster`, `artist` (poster/cover) of `thumb`, `background` (backdrop) bevatten; al het
andere wordt genegeerd. Zie [Naamconventies](08-naming-conventions.md).

**Een handler blijft dezelfde bestanden herverwerken** — de klassieke interactie met RabbitMQ's
`consumer_timeout`: een bericht waarvan het werk langer duurt dan de 30-minuten-consumer-timeout
van de broker wordt opnieuw in de queue gezet en begint opnieuw, eindeloos. De standaardwaarden
beschermen hiertegen (`prefetch=1`, sweeps in chunks — zie de commentaren in `core.properties` en
`disk.properties`); heb je een chunkgrootte verhoogd of de broker-timeout verlaagd, zet dat dan
terug.

**Geen skip-intro-knop op sommige afleveringen** — intro-/outro-detectie vergelijkt afleveringen
binnen een seizoen en stempelt per bestand een detectorversie-markering. Een seizoen verspreid
over meerdere nodes paart alleen de afleveringen die lokaal op elke node staan, en een node met
één losse aflevering detecteert niets. Zie
[Scannen en analyse](../../architecture/nl/02-scanning-and-analysis.md).

**Schijf loopt vol** — controleer of de cache-opschoning nog in dry-run staat (zie hierboven),
en kijk naar de grootte van `CACHE_DIR`/`TMP_DIR` in verhouding tot podcastretentie en
pre-transcode-activiteit.

**Nieuwe bestanden verschijnen niet** — er is geen filesystem-watcher; draai `scanLibraries`.
Worden bestanden wel gevonden maar verkeerd geclassificeerd, vergelijk hun paden dan met
[de verwachte indeling](04-libraries-and-media-layout.md).

Voor een dieper begrip van elk van deze subsystemen begin je bij het
[architectuuroverzicht](../../architecture/nl/00-overview.md).
