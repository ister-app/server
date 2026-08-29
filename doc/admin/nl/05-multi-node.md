---
description: "Draai Ister als zelfgehoste mediaserver op meerdere nodes: gedeelde database en broker, werkrouting per directory en dedicated transcoder-nodes."
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

## Dedicated transcoder-nodes

Een node kan ook transcoderen voor de schijven van **een andere node** zonder zelf media te
bezitten: geef hem geen directories en som in plaats daarvan de directorynamen op die hij moet
bedienen:

```properties
app.ister.transcoder.disks[0].name=server-1-disk1-tv
app.ister.transcoder.disks[1].name=server-1-disk1-movies
```

Is `app.ister.transcoder.disks` leeg, dan valt hij terug op de eigen directories van de node
(het normale single-node-gedrag). Let op: de bronnode moet het bestand nog steeds aan de
transcoder kunnen leveren — externe invoer wordt opgehaald via een download-URL met token.

Let op de spelling van die `disks[n].name`-waarden: ze worden letterlijk als queuenamen gebruikt
en worden **niet gevalideerd** tegen de directories van het cluster. Een typefout levert dus
geruisloos een dode queue op die nooit werk ontvangt — het symptoom is dat transcodes voor die
schijf gewoon op de eigenaarsnode blijven (of nergens draaien).

## Uitgewerkt voorbeeld

`docker-compose-nodes-local.yml` in de repository draait een compleet cluster van drie nodes
tegen één database en broker:

- **server-1** — bezit zes directories (series, films en muziek over twee schijven)
- **server-2** — een tweede volwaardige node met eigen schijven
- **transcoder-1** — geen directories, alleen `app.ister.transcoder.disks[n]`-regels met de
  schijven van server-1: hij doet het transcoderen van server-1

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
