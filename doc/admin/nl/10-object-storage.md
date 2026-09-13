---
description: "Media serveren vanaf S3-compatibele objectopslag (AWS S3, MinIO, Garage, Ceph): S3-library-directories gekoppeld aan meerdere nodes, en de optionele cluster-gedeelde cache- en transcode-opslag."
---

# Objectopslag (S3)

Een library-directory hoeft geen schijf te zijn. Elke S3-compatibele bucket — AWS S3 of een
zelfgehoste MinIO, Garage of Ceph RGW — kan een library bevatten, en één S3-directory kan door
**meerdere nodes tegelijk** bediend worden: elke node die hem configureert scant, analyseert,
transcodeert en streamt hem. Daarbovenop kunnen de afgeleide bestanden (artwork, geëxtraheerde
ondertitels, podcastdownloads) en de HLS-transcode-uitvoer ook naar de bucket, zodat elke node
dezelfde cache en dezelfde segmenten serveert.

Drie dingen staan los van elkaar en zijn elk optioneel:

| Functie | Property | Wat het doet |
| --- | --- | --- |
| S3-library-directory | `app.ister.disk.directories[n].s3-connection` | media in een bucket-prefix, gekoppeld aan N nodes |
| Gedeelde cache | `app.ister.server.cache-s3-connection` | artwork, ondertitels, podcastdownloads in de bucket voor **elke** library op deze node |
| Gedeelde transcode-opslag | `app.ister.server.tmp-s3-connection` | HLS-playlists en -segmenten gepubliceerd naar de bucket, teruggelezen door elke node |

## Connecties

Credentials en endpoints staan in benoemde connecties, alleen in de configuratie — nooit in de
database:

```properties
app.ister.s3.connections[0].name=minio
app.ister.s3.connections[0].endpoint=http://minio:9000   # leeg = AWS
app.ister.s3.connections[0].region=us-east-1
app.ister.s3.connections[0].bucket=ister
app.ister.s3.connections[0].path-style=true             # vereist door de meeste zelfgehoste servers
app.ister.s3.connections[0].access-key=…
app.ister.s3.connections[0].secret-key=…
```

Omgevingsvariabelen werken zoals altijd (`APP_ISTER_S3_CONNECTIONS_0_NAME`, …). Eén connectie is
één bucket; een tweede bucket is een tweede connectie. Laat het endpoint leeg voor AWS; blijven de
sleutels leeg, dan wordt de gewone credential-keten van de SDK gebruikt (instance profile,
omgevingsvariabelen, `~/.aws`).

## Een S3-library-directory

In plaats van een `path` noemt een directory een connectie en, optioneel, een key-prefix:

```properties
app.ister.disk.directories[1].name=shows-s3
app.ister.disk.directories[1].library=shows
app.ister.disk.directories[1].s3-connection=minio
app.ister.disk.directories[1].prefix=media/shows
```

De objecten onder de prefix volgen dezelfde indelingsregels als een schijf ([Libraries en
media-indeling](04-libraries-and-media-layout.md), [Naamconventies](08-naming-conventions.md)); de
"mappen" zijn gewoon key-segmenten. Rijen leggen het object vast als `s3://bucket/key`.

### Nodes koppelen

Een S3-directory heeft **geen eigenaar-node**. De eerste node die ermee start maakt hem aan; elke
andere node die **dezelfde naam met dezelfde connectie en prefix** opsomt wordt eraan gekoppeld en
neemt zijn deel van het werk — alle gekoppelde nodes lezen de queues van de directory, en RabbitMQ
geeft elk bericht aan één van hen. Een scan wordt één keer gestart en draait op de node die hem
oppakt (een databaselock houdt twee overlappende scans uit elkaar). Een node waarvan de regel
dezelfde naam naar een andere bucket of prefix laat wijzen weigert te starten, net als een node die
een lokaal `path` configureert voor een naam die het cluster als S3 kent.

Clients kunnen een S3-bestand vanaf elke gekoppelde node streamen. De node leest het object met
byte-ranges en proxiet het — S3 hoeft nooit bereikbaar te zijn vanaf de client, en standaard ook
niet vanaf ffmpeg: ffmpeg leest via het eigen `/mediaFile/{id}/download` van de node over het
loopback-adres. `app.ister.s3.ffmpeg-direct=true` geeft ffmpeg in plaats daarvan presigned S3-URL's,
wat de hop door de node scheelt maar de bucket blootstelt aan de transcoderende hosts.

Helper-nodes ([Multi-node](05-multi-node.md)) zijn voor S3-directories niet nodig: een node
koppelen *is* de manier om capaciteit toe te voegen. Een helper die een S3-directory onder
`app.ister.helper.disks` opsomt leest hem via een gekoppelde node, zoals bij een schijf.

Alles wat een echt bestand nodig heeft — de epub- en comic-lezer, PDF-rendering, ondertitel-OCR —
downloadt het object eenmalig naar `app.ister.s3.local-copy-dir` (standaard onder de tmp-map) en
houdt het als LRU-cache, begrensd door `app.ister.s3.local-copy-max-bytes` (2 GiB).

## Gedeelde cache

```properties
app.ister.server.cache-s3-connection=minio
app.ister.server.cache-s3-prefix=cache
```

Hiermee schrijft de node elk afgeleid bestand naar `s3://bucket/cache/…` in plaats van naar zijn
`CACHE_DIR`: episode-stills, gedownloade posters, ingebedde en epub-covers, geëxtraheerde
`.srt`-bestanden, podcastdownloads. Het geldt voor **alle** libraries op de node, schijven
inbegrepen. De directory wordt eenmalig geregistreerd als `<clusternaam>-s3-cache` en elke node die
ermee geconfigureerd is wordt gekoppeld, dus podcastdownloads en cache-gescopete events zijn ook
gedeeld werk.

De node-lokale cachemap wordt niet gemigreerd: haar rijen blijven geserveerd door de node die ze
bezit, alleen nieuwe bestanden gaan naar de bucket. De dagelijkse cache-opschoning veegt ook de
gedeelde cache (één node per run, bepaald door een databaselock), met dezelfde dry-run-vlag.

## Gedeelde transcode-opslag

```properties
app.ister.server.tmp-s3-connection=minio
app.ister.server.tmp-s3-prefix=tmp
```

Het encoderen gebeurt nog steeds in de lokale `TMP_DIR` van de node die de pass draait — de
segment-muxer van ffmpeg wil een filesystem — maar elk voltooid segment, elke playlist en elke
voltooiingsmarkering wordt gepubliceerd naar `s3://bucket/tmp/{mediaFileId}/…`, en een node die
afspelen bedient en een bestand lokaal mist leest het daarvandaan terug. Een client kan dus op node
A beginnen met afspelen terwijl node B de pass draait, en een bestand dat gisteren op welke node dan
ook getranscodeerd is speelt vandaag op elke node meteen. De node-naar-node-segmentupload van de
multi-node-opzet met schijven wordt niet gebruikt als dit aanstaat.

De opslag wordt samen met de lokale tmp-opschoning geveegd (zelfde schema, `min-age` en dry-run):
de gepubliceerde map van een mediabestand verdwijnt als het bestand weg is of als er binnen het
venster niets meer voor gepubliceerd is.

## Lokaal ontwikkelen

`docker-compose-local.yml` bevat een MinIO (`minio`, console op poort 9001,
`minioadmin`/`minioadmin`) plus een one-shot die de bucket `ister` aanmaakt; het uitgecommentarieerde
environment-blok op de `server`-service wijst er een library naartoe. De integratietests draaien
dezelfde server via Testcontainers.

## Verder lezen

- [Multi-node](05-multi-node.md) — gekoppelde nodes naast eigenaar-nodes en helpers
- [Configuratie](03-configuration.md#objectopslag-s3) — elke property in één tabel
