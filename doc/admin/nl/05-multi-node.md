---
description: "Draai Ister als zelfgehoste mediaserver op meerdere nodes: gedeelde database en broker, werkrouting per directory en helper-nodes voor transcoderen, intro-detectie en ondertitel-OCR."
---

# Multi-node

Eén Ister-deployment kan meerdere servers ("nodes") beslaan. Typische redenen: media verspreid
over machines in verschillende kamers, of een krachtige machine die het transcoderen doet voor
een NAS die de bestanden bewaart. Alle nodes delen **één PostgreSQL-database en één
RabbitMQ-broker**; clients kunnen met elke node praten.

## Het concept

Elke node draait **dezelfde applicatie-image** met dezelfde database-/RabbitMQ-instellingen, en
verschilt alleen in:

- `app.ister.server.name` — uniek per node (deze naam wordt ook gebruikt voor de
  cache-directory-queue van de node, zie hieronder)
- `app.ister.server.url` — hoe clients *en de andere nodes* hem bereiken
- `app.ister.cluster.name` — identiek op elke node
- de `app.ister.disk.directories[n].*`-regels voor de schijven die **deze** node fysiek heeft

Het opstarten valideert de multi-node-configuratie, en een conflict is fataal: een directorynaam
die al door een andere node geclaimd is gooit
`IllegalStateException: Directory <naam> name is already used by an other node` en de node
**start niet**. Sterft een net toegevoegde node dus meteen, kijk dan eerst in zijn log naar deze
melding.

## Hoe werk wordt gerouteerd

De meeste achtergrondqueues zijn **directory-scoped**: de queuenaam bevat de directorynaam
(bijv. `app.ister.server.TranscodeRequested.disk1`), en elke node luistert alleen op de queues
van directories die hij bezit. Als een client dus aan een willekeurige node een stream vraagt,
belandt het transcodeverzoek op de node die het bronbestand heeft — geen gedeeld filesystem
nodig. Dit is ook waarom directorynamen uniek moeten zijn binnen het cluster.

Naast zijn library-directories luistert elke node op één **cache-directory-queue** met de naam
`<serverName>-cache-directory` (aangemaakt bij het opstarten; podcastdownloads lopen er
bijvoorbeeld doorheen zodat de audio in de cache van de juiste node belandt). De routering loopt
over de server*naam*, dus ook `app.ister.server.name` moet cluster-uniek zijn — niet alleen voor
de weergave.

Wanneer node A transcodeert voor een afspeelsessie die node B bedient, pusht A elk voltooid
HLS-segment naar B via `POST /transcode/upload/{id}/{fileName}`, geauthenticeerd met
kortlevende **node-tokens** die de nodes onderling automatisch uitgeven en verversen (elke 12
uur ververst). Je configureert hiervoor niets, behalve correcte `app.ister.server.url`-waarden —
maar die URL's moeten node-naar-node bereikbaar zijn, niet alleen vanuit je browser.

## Helper-nodes

Elke node blijft verantwoordelijk voor zijn eigen directories. Daarbovenop kan een krachtige
node andere nodes **helpen** met de CPU-zware jobfamilies — hij hoeft daarvoor zelf geen media
te bezitten:

| Job | Wat eronder valt |
| --- | --- |
| `TRANSCODE` | HLS-(pre)transcoding |
| `DETECT_SEGMENTS` | intro-/outro-detectie (audio-fingerprinting) |
| `SUBTITLES` | extractie van ingebedde ondertitels, inclusief OCR van dvd-/blu-ray-bitmapondertitels |

Som de directorynamen op die de helper moet bedienen, en optioneel welke jobs:

```properties
app.ister.helper.disks[0].name=server-1-disk1-tv
app.ister.helper.disks[1].name=server-1-disk1-movies
app.ister.helper.disks[1].jobs=DETECT_SEGMENTS,SUBTITLES
# standaard jobset voor schijven zonder eigen "jobs" (standaard: alle drie)
app.ister.helper.jobs=TRANSCODE,DETECT_SEGMENTS,SUBTITLES
```

De helper consumeert dan **dezelfde queues** als de eigenaar voor die directories, en RabbitMQ
verdeelt het werk bericht voor bericht tussen beide — de snelste node pakt vanzelf het meest. De
helper leest het bronbestand over HTTP van de eigenaar (een download met token en
byte-ranges, zodat seeken goedkoop blijft); intro-detectie schrijft alleen databaserijen, en een
geëxtraheerde ondertitel wordt geüpload naar de cache-directory van de eigenaar, die hem serveert
alsof hij hem zelf gemaakt had. Daarvoor is niets nodig behalve correcte
`app.ister.server.url`-waarden, maar de helper heeft dezelfde tools nodig als elke node (ffmpeg,
mkvextract, subtile-ocr) — gebruik dezelfde image.

Een eigenaar die zijn eigen CPU helemaal niet aan een jobfamilie wil besteden, kan die volledig
uit handen geven:

```properties
# op de eigenaarsnode
app.ister.helper.offload-jobs=DETECT_SEGMENTS,SUBTITLES
```

Een helper leest ook de clusterbrede worker-queues mee (metadata, podcast-refresh,
zoekindex) zoals elke node, maar bewaart nooit zelf podcastdownloads: die geeft hij door aan
de node die de libraries bedient, en hij plant zelf geen podcast-refreshes.

Zijn queues voor die jobs worden nog steeds gedeclareerd en gevuld, maar alleen door helpers
geconsumeerd. Draait er geen helper, dan wacht het werk gewoon op de queue (zichtbaar als
queue-diepte op de clusterpagina) — er gaat niets verloren, en er draait niets tot er een helper
verschijnt. Transcoderen voor de eigen cache-directory van de node (podcastdownloads) wordt nooit
uit handen gegeven.

Let op de spelling van de `disks[n].name`-waarden: ze worden letterlijk als queuenamen gebruikt.
Het opstarten zoekt elke naam op in de directories van het cluster en logt een **waarschuwing**
voor een naam die hij niet kent (hij faalt niet: de eigenaarsnode kan simpelweg nog niet
draaien). Een verkeerd gespelde naam laat de helper luisteren op een queue waar niemand naar
publiceert.

`app.ister.transcoder.disks[n].name` uit eerdere versies werkt nog (het wordt vertaald naar
helper-schijven met alleen de `TRANSCODE`-job en vervangt, zoals voorheen, de eigen directories
van de node voor het transcoderen) en logt een deprecatiewaarschuwing; verplaats het naar
`app.ister.helper.disks`.

## Uitgewerkt voorbeeld

`docker-compose-nodes-local.yml` in de repository draait een compleet cluster van drie nodes
tegen één database en broker:

- **server-1** — bezit zes directories (series, films en muziek over twee schijven)
- **server-2** — een tweede volwaardige node met eigen schijven
- **helper-1** — geen directories, alleen `app.ister.helper.disks[n]`-regels met de schijven van
  server-1: hij transcodeert voor server-1 en doet diens intro-detectie en ondertitel-OCR, die
  server-1 volledig uit handen geeft (`APP_ISTER_HELPER_OFFLOAD_JOBS`)

Alle drie de nodes hebben in het voorbeeld VAAPI-hardwareversnelling ingeschakeld — transcoderen
kan op elk van hen belanden, dus hardwareversnelling is het configureren waard op elke node die
transcodeert.

Punten om over te nemen: elke node heeft zijn **eigen** `CACHE_DIR`, een eigen gepubliceerde
poort en een `server.url` met een echt LAN-IP (niet `localhost` — de andere nodes moeten hem
bereiken), terwijl `APP_ISTER_CLUSTER_NAME` overal hetzelfde is.

## Operationele opmerkingen

- De clusterpagina in de client (Instellingen → Cluster) toont elke node en zijn gezondheid —
  de snelste "draait alles?"-check.
- Scans, metadata en opschoning draaien per node voor de directories die hij bezit; je start
  `scanLibraries` één keer en elke node pakt zijn eigen deel op.
- **Houd de klok van elke node NTP-gedisciplineerd.** Listen-along-clients peilen het
  ongeauthenticeerde `/time`-endpoint om hun klok-offset te meten, en device-presence loopt
  cluster-breed over de status-exchange — omdat clients met elke node kunnen praten, hangt de
  gemeten offset af van welke node antwoordt. Nodes met een afdrijvende klok laten de
  listen-along-synchronisatie wiebelen.
- Voor de interne werking van transcoderen over nodes heen, zie de
  [architectuurdocumentatie](../../architecture/nl/04-transcoding.md).

## Verder lezen

- [Zoeken](06-search-typesense.md) — één Typesense bedient het hele cluster
- [Onderhoud](07-maintenance-and-troubleshooting.md) — caches en taken per node
