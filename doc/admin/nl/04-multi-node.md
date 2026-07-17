# Multi-node

Eén Ister-deployment kan meerdere servers ("nodes") beslaan. Typische redenen: media verspreid
over machines in verschillende kamers, of een krachtige machine die het transcoderen doet voor
een NAS die de bestanden bewaart. Alle nodes delen **één PostgreSQL-database en één
RabbitMQ-broker**; clients kunnen met elke node praten.

## Het concept

Elke node draait **dezelfde applicatie-image** met dezelfde database-/RabbitMQ-instellingen, en
verschilt alleen in:

- `app.ister.server.name` — uniek per node
- `app.ister.server.url` — hoe clients *en de andere nodes* hem bereiken
- `app.ister.cluster.name` — identiek op elke node
- de `app.ister.disk.directories[n].*`-regels voor de schijven die **deze** node fysiek heeft

Het opstarten valideert de multi-node-configuratie en logt problemen, dus bekijk het log van
een net toegevoegde node.

## Hoe werk wordt gerouteerd

De meeste achtergrondqueues zijn **directory-scoped**: de queuenaam bevat de directorynaam
(bijv. `app.ister.server.transcode_requested.disk1`), en elke node luistert alleen op de queues
van directories die hij bezit. Als een client dus aan een willekeurige node een stream vraagt,
belandt het transcodeverzoek op de node die het bronbestand heeft — geen gedeeld filesystem
nodig. Dit is ook waarom directorynamen uniek moeten zijn binnen het cluster.

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

## Uitgewerkt voorbeeld

`docker-compose-nodes-local.yml` in de repository draait een compleet cluster van drie nodes
tegen één database en broker:

- **server-1** — bezit zes directories (series, films en muziek over twee schijven), VAAPI ingeschakeld
- **server-2** — een tweede volwaardige node met eigen schijven
- **transcoder-1** — geen directories, alleen `app.ister.transcoder.disks[n]`-regels met de
  schijven van server-1: hij doet het transcoderen van server-1

Punten om over te nemen: elke node heeft zijn **eigen** `CACHE_DIR`, een eigen gepubliceerde
poort en een `server.url` met een echt LAN-IP (niet `localhost` — de andere nodes moeten hem
bereiken), terwijl `APP_ISTER_CLUSTER_NAME` overal hetzelfde is.

## Operationele opmerkingen

- De clusterpagina in de client (Instellingen → Cluster) toont elke node en zijn gezondheid —
  de snelste "draait alles?"-check.
- Scans, metadata en opschoning draaien per node voor de directories die hij bezit; je start
  `scanLibrary` één keer en elke node pakt zijn eigen deel op.
- Voor de interne werking van transcoderen over nodes heen, zie de
  [architectuurdocumentatie](../../architecture/nl/04-transcoding.md).

## Verder lezen

- [Zoeken](05-search-typesense.md) — één Typesense bedient het hele cluster
- [Onderhoud](06-maintenance-and-troubleshooting.md) — caches en taken per node
