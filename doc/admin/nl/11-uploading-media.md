---
description: "Media uploaden naar een Ister-library vanuit de player: alleen voor admins, met een preview van hoe de scanner elk bestand herkent, hervatbare overdracht in chunks, en de instellingen die een reverse proxy en een read-only media-mount nodig hebben."
---

# Media uploaden

Een beheerder kan vanuit de player media aan een library toevoegen in plaats van bestanden op de
server te kopiëren: een hele serie, één album bij een artiest die er al staat, of een map vol
artiesten. De bestanden komen terecht in de mapstructuur die de scanner verwacht, en elk bestand
wordt opgepikt zodra het compleet is — een library-scan is niet nodig.

Alleen gebruikers met de rol `admin` kunnen uploaden. De endpoints accepteren uitsluitend de
gewone login (bearer-token); de stream-tokens die players voor afspelen gebruiken worden hier
bewust niet geaccepteerd.

## Zo werkt een upload

1. **Kies waar het heen gaat.** Een library, en daarna een van de directories ervan (een schijf, of
   een S3-directory). De player toont per directory de vrije ruimte en of de server er überhaupt
   kan schrijven.
2. **Kies de map die je uploadt**, en waar die terechtkomt:
   - *onder de root van de directory* — een seriemap, een artiestmap;
   - *onder een bestaande map* — een album onder zijn artiest;
   - *zonder de gekozen map zelf* — een map vol artiesten: de submappen worden de mappen op het
     hoogste niveau.
3. **Preview.** Voordat er één byte verstuurd wordt, vertelt de server per bestand wat de scanner
   ervan maakt: serie / seizoen / aflevering, artiest / album / track, auteur / boek / hoofdstuk,
   reeks / deel. De preview gebruikt de regels van de scanner zelf, dus wat je ziet is wat je
   krijgt. Je ziet ook op welk *niveau* de hoofdmap herkend wordt — een album dat in de root van
   een muziekdirectory belandt verschijnt als `ARTIST`, en dat is het signaal om de artiestmap als
   bovenliggende map te kiezen. De mapnaam kun je hier aanpassen (bijvoorbeeld om de `(2019)` toe
   te voegen die een seriemap nodig heeft, zie [Naamgevingsconventies](08-naming-conventions.md)).
4. **Uploaden.** Bestanden gaan in chunks omhoog. Een weggevallen verbinding, een dichtgeklapte
   laptop of een herstarte server kost hooguit één chunk: de upload gaat verder waar hij was.

Wat de preview over een bestand kan zeggen:

| Status | Betekenis |
|---|---|
| Herkend | Wordt geüpload en opgepikt zoals getoond |
| Genegeerd | De scanner zou het op die plek niet oppikken (niet-ondersteund bestandstype, of een mapniveau waar de scan niet in afdaalt). Wordt niet geüpload |
| Bestaat al | Het bestand staat er al. Overgeslagen, tenzij je voor deze upload **Overschrijven** aanzet |
| Bezet | Een andere lopende upload schrijft hetzelfde bestand |
| Ongeldig | Het pad kan niet worden opgeslagen (bijvoorbeeld een naam die met een punt begint) |

### Overschrijven

Met **Overschrijven** aan wordt een bestaand bestand vervangen en opnieuw geanalyseerd (streams,
duur, hoofdstukken, artwork). Wie precies dat bestand op dat moment afspeelt ziet het afspelen
afbreken: de getranscodeerde segmenten worden samen met het oude bestand weggegooid.

## Vereisten

### Een schrijfbare media-mount

Veel installaties mounten hun media read-only. Dat is prima voor afspelen en scannen, maar niet
voor uploads. De player markeert zo'n directory als niet-schrijfbaar. Mount hem read-write voor de
server (de container-gebruiker heeft schrijfrechten op de root van de directory nodig) om erin te
kunnen uploaden.

Zolang een upload loopt, staan de bytes in een verborgen map `.ister-upload/` in de root van de
doeldirectory — op hetzelfde bestandssysteem, zodat het op zijn plek zetten van een voltooid
bestand van 40 GB een rename is en geen tweede kopie. Scans slaan die map over. Hij verdwijnt
wanneer de upload klaar is, geannuleerd wordt of verloopt.

### Reverse proxy

Elke chunk is één HTTP-request met standaard een body van 16 MB. De proxy vóór Ister moet een
request-body van die grootte accepteren en mag hem niet met een korte timeout naar schijf bufferen:

- nginx: `client_max_body_size 32m;` en `proxy_request_buffering off;`
- Envoy / Gateway API: standaard geen limiet op de body; controleer de route-timeout bij trage
  uplinks
- een kleinere chunkgrootte (`UPLOAD_CHUNK_SIZE`, minimaal 5 MB) ruilt doorvoer in voor
  vriendelijkere requests

### Multi-node

Een chunk wordt geschreven waar hij aankomt en wordt nooit tussen nodes doorgestuurd. De player
stuurt de upload daarom rechtstreeks naar de node die de gekozen directory serveert (de eigenaar
van een lokale directory, een gekoppelde node van een S3-directory) — dezelfde node-URL waar hij
de media van die directory al vandaan streamt. Die URL moet bereikbaar zijn voor de player van de
beheerder, zie [Multi-node](05-multi-node.md).

### S3-directories

Een upload naar een S3-directory is een S3-multipart-upload, één part per chunk; bestanden groter
dan de limiet van 5 GB voor een enkele request zijn dus geen probleem. De credentials van de
connectie hebben naast de leesrechten `s3:PutObject` en `s3:AbortMultipartUpload` nodig.

Ister breekt de multipart-uploads van geannuleerde en verlopen sessies zelf af. Als vangnet voor de
gevallen die het niet kan zien (een database-restore, een bucket die met iets anders gedeeld wordt)
stel je een lifecycle-regel op de bucket in die onvoltooide multipart-uploads na een paar dagen
afbreekt.

## Instellingen

| Omgevingsvariabele | Standaard | Betekenis |
|---|---|---|
| `UPLOAD_ENABLED` | `true` | Zet de upload-endpoints helemaal uit |
| `UPLOAD_CHUNK_SIZE` | `16MB` | Grootte van één chunk-request (minimaal 5 MB). Heel grote bestanden krijgen automatisch grotere chunks, om onder S3's limiet van 10.000 parts te blijven |
| `UPLOAD_MAX_ACTIVE_SESSIONS` | `2` | Uploads die tegelijk mogen lopen, clusterbreed |
| `UPLOAD_MAX_FILES_PER_SESSION` | `20000` | Bestanden in één upload |
| `UPLOAD_MAX_CONCURRENT_CHUNKS` | `4` | Chunk-requests die één node tegelijk afhandelt; meer krijgen het verzoek het opnieuw te proberen |
| `UPLOAD_SESSION_IDLE_TIMEOUT` | `24h` | Een upload die zo lang niets ontving verloopt, en de gestagede bytes worden verwijderd |
| `UPLOAD_MIN_FREE_SPACE` | `5GB` | Ruimte die een lokale directory na de upload vrij moet houden; een upload die niet past wordt vooraf geweigerd |
| `UPLOAD_CLEANUP_INTERVAL` | `PT1H` | Hoe vaak verlopen uploads worden opgeruimd |

## Problemen oplossen

- **"Niet schrijfbaar" in de directory-kiezer** — de mount is read-only voor het serverproces.
- **Upload geweigerd met "niet genoeg ruimte"** — de vrije ruimte min wat aan andere lopende
  uploads is toegezegd min `UPLOAD_MIN_FREE_SPACE` is kleiner dan de upload.
- **Chunks falen met 413** — de body-limiet van de reverse proxy ligt onder de chunkgrootte.
- **Een bestand staat op "Genegeerd"** — het staat niet op een plek waar de scanner kijkt.
  Controleer het niveau dat bij de hoofdmap getoond wordt en de
  [naamgevingsconventies](08-naming-conventions.md).
- **Een upload blijft hangen op "te veel uploads bezig"** — rond een andere af of annuleer hem, of
  wacht tot de idle-timeout een verlaten upload laat verlopen.

## Verder lezen

- [Libraries en media-indeling](04-libraries-and-media-layout.md) — de mapstructuur per library-type
- [Naamgevingsconventies](08-naming-conventions.md) — wat de scanner herkent
- [Objectopslag (S3)](10-object-storage.md) — S3-directories
