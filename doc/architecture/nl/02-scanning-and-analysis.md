---
description: Hoe Ister medialibraries scant en analyseert, van startup-bootstrap en routering per bestandstype tot heranalyse per item en de BlurHash-sweep.
---

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
| Audio | `AUDIO_FILE_FOUND` | ffprobe, ID3-tags (titel/tracknr, track-artiest uit de `artist`-tag met de pad-artiest als fallback), embedded cover, HLS-cache leegmaken |
| `.epub` (BOOK-library) | `EPUB_FILE_FOUND` | OPF: titel/taal/beschrijving, media overlays uit de inhoud, cover uit de zip |
| `.cbz`/`.pdf`/`.epub` (COMIC-library) | `COMIC_FILE_FOUND` (epubs hergebruiken `EPUB_FILE_FOUND`) | paginatelling, `ComicInfo.xml`, cover extraheren |
| `.srt` | `SUBTITLE_FILE_FOUND` | SRT als `EXTERNAL_SUBTITLE`-stream aan de episode koppelen |
| Afbeelding | `IMAGE_FOUND` | `ImageEntity` opslaan, koppelen aan show/movie/episode/etc. |
| `.nfo` | `NFO_FILE_FOUND` | XML parsen: titel, beschrijving, releasedatum, biografie/review |

Entity-creatie loopt via `ScannerHelperService.getOrCreate*`, dat ook de `*_FOUND`-verrijkingsevents
en de creatie-events voor de zoekindex afvuurt.

### Multi-episode-bestanden

Een bestandsnaam mag een aflevering-**range** dragen — `s04e06-e07.mkv`, `s04e06-08.mkv`,
`s04e06e07.mkv` — voor een bestand met maximaal drie opeenvolgende afleveringen. `PathObject` parst
de range (een onwaarschijnlijke range, achterstevoren of langer dan drie, valt terug op de eerste
aflevering) en `MediaFileScanner` maakt per aflevering een eigen `EpisodeEntity`, zodat elke z'n
eigen TMDB-metadata en watch status krijgt. De FK `episode_entity_id` van het bestand wijst altijd
naar de **eerste** aflevering; elke bevatte aflevering (ook de eerste) krijgt daarnaast een
`media_file_episode_entity`-linkrij met z'n `start`/`duration`-slice binnen het bestand. Geen
linkrijen betekent "gewoon single-episode-bestand" — alle bestaande FK-queries blijven correct, en
afspeelpaden zoeken bestanden op via `MediaFileEpisodeService.filesForEpisode`.

De slicegrenzen worden berekend in `HandleMediaFileFound` (`MediaFileFoundEpisodeBoundaries`) zodra
ffprobe de bestandsduur kent: één MKV-chapter per aflevering wordt direct gebruikt; bij meer
chapters (scene-markers) wint de chapter het dichtst bij elk gelijk-verdeel-punt, tenzij dat een
aflevering onwaarschijnlijk kort zou maken; anders wordt de duur gelijk verdeeld. Elke aflevering
krijgt ook een eigen backdrop-still, genomen op het midden van z'n eigen slice. Bestanden die
**vóór** multi-episode-ondersteuning zijn gescand worden gebackfilld door een gewone
library-rescan: de scanner ziet een bestaand bestand waarvan het pad als range parst maar zonder
linkrijen, maakt de ontbrekende afleveringen en links aan en stuurt `MEDIA_FILE_FOUND` opnieuw,
zodat de grenzen en stills worden berekend.

Sidecar-bestanden (NFO, lokale afbeeldingen, externe ondertitels) hangen zoals voorheen aan de
eerste aflevering van de range.

### Intro/outro-detectie

Terugkerende intro's en aftitelingen worden gevonden door **audio over een seizoen te
vergelijken**: de disk-module decodeert korte vensters (eerste 10 / laatste 4 minuten van de slice
van elke aflevering) naar mono-PCM, fingerprint ze (`ChromaFingerprinter`, een chromaprint-achtige
32-bits gradiënthash per 128 ms — ongevoelig voor volumeverschillen, zonder externe library), en
`SegmentMatcher` zoekt de langste gedeelde run tussen een aflevering en maximaal vier
seizoensburen. De mediaan van de grenzen over instemmende paren (minimaal twee, één bij seizoenen
van twee afleveringen) wordt opgeslagen als `media_file_segment_entity`-rijen (`INTRO`/`OUTRO`) in
**absolute bestandstijd**; in een multi-episode-bestand krijgt elke slice eigen rijen,
gedisambigueerd via `episode_entity_id`. De player leest ze als `MediaFile.segments` voor zijn
intro-overslaan- en volgende-aflevering-knoppen.

Omdat de detectie afleveringen onderling vergelijkt kan ze niet in de per-bestand
`MEDIA_FILE_FOUND`-handler draaien: die vuurt in plaats daarvan een seizoens-gescopeerd
`DETECT_SEGMENTS`-event **nadat zijn transactie is gecommit**, op dezelfde directory-gescopeerde
queue-familie, zodat de detectie draait op de node die de bestanden bezit. `HandleDetectSegments`
is idempotent — bestanden waarvan `media_file_entity.segment_detector_version` al gelijk is aan de
huidige detectorversie dienen alleen nog als vergelijkingsmateriaal — dus één event per
geanalyseerde aflevering is prima: het event van de laatste aflevering doet het echte werk. De
versiekolom is tegelijk de sentinel voor "gedraaid maar niets gevonden"; `null` betekent dat
detectie nooit liep, en de backfill van de scanner (`app.ister.server.segment-detect-backfill`,
standaard aan, één keer per seizoen per run) stuurt bij een rescan `DETECT_SEGMENTS` voor zulke
bestanden. Heranalyse per item wist de segmentrijen en reset de versie, en het ophogen van
`HandleDetectSegments.DETECTOR_VERSION` laat de detectie overal opnieuw draaien via dezelfde
backfill. Bekende beperking: een seizoen verspreid over meerdere nodes paart alleen de
afleveringen die lokaal op elke node staan — een node met één losse aflevering detecteert er niets
voor.

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

De `*_FOUND`-events worden pas gepubliceerd **nadat de wipe gecommit is**
(`AfterCommitPublisher.publishAfterCommit`): hun consumers controleren op bestaande metadata- en
image-rijen en zouden anders de ten dode opgeschreven rijen nog zien staan en de refetch overslaan,
waardoor het item blijvend zonder covers achterblijft. Voor albums stuurt disk-`HandleAlbumFound`
daarnaast `FILE_SCAN_REQUESTED` voor lokale artwork (`cover.jpg` en verwanten) in de albummap — de
album-analyse wist ook die image-rijen, en anders dan bij films/afleveringen volgt er geen
directory-rescan, dus worden de bestanden expliciet opnieuw ingelezen (door `ImageScanner` gededupt
op de bestaande `(directory, path)`-rij).

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
