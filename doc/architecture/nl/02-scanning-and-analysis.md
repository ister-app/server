# Scannen en analyseren

Twee losse flows vullen de database: **scannen** registreert wat er op disk staat, **analyseren**
verrijkt dat met metadata van externe providers. Beide worden getriggerd vanuit `ScannerController`
(GraphQL-mutations `scanLibrary()` / `analyzeLibrary()`), plus heranalyse-mutations per item.

## Startup-bootstrap

`disk/.../StartupTasks` luistert op Spring's `ContextRefreshedEvent` — bij startup worden geen
RabbitMQ-events verstuurd. Het maakt of updatet `NodeEntity`-, `LibraryEntity`- en
`DirectoryEntity`-rijen op basis van de configuratieproperties (`disk.properties` / env vars),
maakt de cache-directories op disk aan en valideert de multi-node-configuratie. Zie het
[startup-diagram](../diagrams/startup.md).

## Library scannen

Zie het [scan-flow-diagram](../diagrams/scan-flow.md). `scanLibrary()` stuurt per directory een
`NEW_DIRECTORIES_SCAN_REQUEST`; de disk-handler loopt door het filesystem en stuurt per bestand één
`FILE_SCAN_REQUESTED`. `FileScanRequestedHandle` routeert op extensie (en library-type):

| Bestand | Event | Wat de handler doet |
| --- | --- | --- |
| Video | `MEDIA_FILE_FOUND` | ffprobe: streams + duur, embedded subs naar SRT extraheren, screenshot als achtergrond |
| Audio | `AUDIO_FILE_FOUND` | ffprobe, ID3-tags (titel/artiest/tracknr), embedded cover, HLS-cache leegmaken |
| `.epub` (BOOK-library) | `EPUB_FILE_FOUND` | OPF: titel/taal/beschrijving, media overlays uit de inhoud, cover uit de zip |
| `.cbz`/`.pdf`/`.epub` (COMIC-library) | `COMIC_FILE_FOUND` (epubs hergebruiken `EPUB_FILE_FOUND`) | paginatelling, `ComicInfo.xml`, cover extraheren |
| `.srt` | `SUBTITLE_FILE_FOUND` | SRT als `EXTERNAL_SUBTITLE`-stream aan de episode koppelen |
| Afbeelding | `IMAGE_FOUND` | `ImageEntity` opslaan, koppelen aan show/movie/episode/etc. |
| `.nfo` | `NFO_FILE_FOUND` | XML parsen: titel, beschrijving, releasedatum, biografie/review |

Entity-creatie loopt via `ScannerHelperService.getOrCreate*`, dat ook de `*_FOUND`-verrijkingsevents
en de creatie-events voor de zoekindex afvuurt.

## Library analyseren

Zie het [analyze-flow-diagram](../diagrams/analyze-flow.md). `analyzeLibrary()` stuurt per node een
`ANALYZE_LIBRARY_REQUEST`; de worker zoekt alles op waar metadata of afbeeldingen **ontbreken** en
waaiert uit: `SHOW_FOUND` / `EPISODE_FOUND` / `MOVIE_FOUND` (TMDB), `PERSON_FOUND` / `ALBUM_FOUND`
(MusicBrainz + NFO-lookup aan de disk-kant), `AUDIO_FILE_FOUND` voor tracks zonder metadata, en
`UPDATE_IMAGES_REQUESTED` per directory voor de BlurHash-sweep. De pijplijnen per type staan in
[hoofdstuk 3](03-media-types-and-metadata.md).

## Heranalyse per item

Mutations als `analyzeShow(id)` en `analyzeMovie(id)` sturen `ANALYZE_DATA`, dat door **twee**
handlers geconsumeerd wordt: `AnalyzeDataHandle` (worker) wist de metadata/afbeeldingen/streams van
het item en cascadeert — een library waaiert uit naar al zijn shows/films/artiesten, een show naar
zijn afleveringen, een album naar zijn tracks — en vuurt de `*_FOUND`-events opnieuw af;
`HandleAnalyzeDataDisk` (disk) wist de HLS-cache en stuurt de bestandsniveau-events opnieuw
(`MEDIA_FILE_FOUND`/`AUDIO_FILE_FOUND`, `NFO_FILE_FOUND`, `SUBTITLE_FILE_FOUND`).

## De BlurHash-sweep

`HandleImageFound` slaat afbeeldingen bewust **zonder** BlurHash op: die coderen is CPU-duur en
maakte die handler de bottleneck bij grote scans. De hashes worden achteraf gevuld door de
`UPDATE_IMAGES_REQUESTED`-sweep, per directory — de **cache-directory inbegrepen**, want daar staat
de gedownloade artwork en dus de overgrote meerderheid van de afbeeldingen.

Elk bericht verwerkt hoogstens `app.ister.server.blur-hash.chunk-size` afbeeldingen en publiceert
daarna een opvolgerbericht met een keyset-cursor (`afterId`). Eén sweep over een hele library in één
bericht duurde vroeger langer dan RabbitMQ's `consumer_timeout` (30 minuten), waarna het bericht
teruggezet werd en de sweep eindeloos opnieuw begon zonder ooit te committen.

Twee subtiliteiten:

- De cursor is een **keyset op `id`** — geen offset en geen "eerstvolgende rij zonder hash". Een
  afbeelding die nooit te hashen is (een CMYK-JPEG die `ImageIO` niet kan lezen) houdt
  `blur_hash NULL`; een naïeve `LIMIT`-query zou zulke rijen elke ronde opnieuw selecteren en nooit
  eindigen. PostgreSQL sorteert `uuid` unsigned terwijl `java.util.UUID.compareTo` signed
  vergelijkt, dus zowel de `ORDER BY` als de `id >`-vergelijking moeten **in de database** draaien,
  nooit in Java.
- Het opvolgerbericht wordt pas gepubliceerd **nadat** de transactie van de chunk gecommit is
  (`BlurHashChunkProcessor`). Andersom zou een mislukte commit een cursor achterlaten die voorbij
  nooit-opgeslagen werk wijst.

## Verwante scheduled jobs

`CacheCleanupScheduler` (disk) en `TmpTranscodeCleanupScheduler` (transcoder) draaien dagelijks een
zombie-sweep over de image-cache en de transcode-tmp-dirs: bestanden waar geen enkele database-rij
meer naar verwijst worden verwijderd, en oude podcastdownloads verlopen.
**`app.ister.server.cache-cleanup.dry-run` staat standaard op `true`** — de cleanup logt alleen
totdat die vlag omgezet wordt.
