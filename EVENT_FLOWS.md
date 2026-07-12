# Event Flows — Ister Server

Ister Server werkt volledig event-driven via RabbitMQ. Elke significante actie wordt asynchroon verwerkt door handlers die de `Handle<T>` interface implementeren.

**Queue-naampatroon:** `app.ister.server.<EventName>[.<scope>]`
(scope = directorynaam, nodenaam, of leeg voor globale queues)

---

## Startup

`StartupTasks` luistert op Spring's `ContextRefreshedEvent` en initialiseert de database — er worden geen RabbitMQ-events verstuurd.

```mermaid
flowchart LR
    A([Application Start]) --> B[ContextRefreshedEvent]
    B --> C[StartupTasks]
    C --> D[(NodeEntity)]
    C --> E[(LibraryEntity)]
    C --> F[(DirectoryEntity)]
    C --> G[Cache-directory aanmaken]
```

---

## Flow 1: Library Scannen

**Trigger:** GraphQL mutation `scanLibrary()` in `ScannerController`

```mermaid
flowchart TD
    API([scanLibrary API]) -->|stuurt| A

    A["NEW_DIRECTORIES_SCAN_REQUESTED\n.{dirName}"]
    A --> B["HandleNewDirectoriesScanRequested\n📦 disk"]
    B -->|loopt door filesystem| C

    C["FILE_SCAN_REQUESTED\n.{dirName} — per bestand"]
    C --> D["FileScanRequestedHandle\n📦 disk\nrouteert op extensie"]

    D -->|video| E["MEDIA_FILE_FOUND\n.{dirName}"]
    D -->|audio| F["AUDIO_FILE_FOUND\n.{dirName}"]
    D -->|".epub (BOOK-library)"| EP["EPUB_FILE_FOUND\n.{dirName}"]
    D -->|.srt| G["SUBTITLE_FILE_FOUND\n.{dirName}"]
    D -->|afbeelding| H["IMAGE_FOUND\n.{dirName}"]
    D -->|.nfo| I["NFO_FILE_FOUND\n.{dirName}"]

    E --> J["HandleMediaFileFound\n📦 disk"]
    J -->|"ffprobe: streams + duur\nembedded subs → SRT\nscreenshot → achtergrond"| K["IMAGE_FOUND\n.{dirName}"]

    F --> L["HandleAudioFileFound\n📦 disk"]
    L -->|"ffprobe: streams + duur\nID3-tags: titel, artiest, tracknr\nembedded albumhoes\nHLS-cache leegmaken"| M["IMAGE_FOUND\n.{dirName}"]

    G --> N["HandleSubtitleFileFound\n📦 disk"]
    N -->|"koppelt SRT aan episode\nMaakt MediaFileStreamEntity\nEXTERNAL_SUBTITLE"| DB1[(Database)]

    K --> O["HandleImageFound\n📦 disk"]
    M --> O
    H --> O
    O -->|"BlurHash genereren\nImageEntity opslaan\nkoppelen aan Show/Movie/Episode/etc."| DB2[(Database)]

    I --> P["HandleNfoFileFound\n📦 disk"]
    P -->|"Parseert XML NFO\ntitel, beschrijving, releasedatum\nbiografie/review voor muziek/boeken"| DB3[(Database)]

    EP --> Q["HandleEpubFileFound\n📦 disk"]
    Q -->|"leest OPF: titel, taal, beschrijving\nmedia overlays op inhoud (SMIL)\ncover uit de zip naar cache"| R["IMAGE_FOUND\n.{cacheDirName}"]
    R --> O
```

**Boeken (LibraryType BOOK):** de structuur is `Auteur/Boek.epub` en `Auteur/Boek/NNN_Hoofdstuk.mp3`.
Epubs, audiobook-mp3's, karaoke-epubs met hetzelfde (genormaliseerde) boeknaam convergeren op één
`BookEntity` (auteur = `PersonEntity`). Audiobook-mp3's volgen dezelfde `AUDIO_FILE_FOUND`-pijplijn
als muziek — `HandleAudioFileFound` brancht op library-type en koppelt `ChapterEntity` i.p.v.
`TrackEntity`. `getOrCreateBook`/`getOrCreateChapter` vuren `BOOK_FOUND`/`CHAPTER_FOUND`;
`BOOK_FOUND` wordt in de worker opgepakt voor Open Library-verrijking. Of een epub media overlays
heeft (voorleesaudio) wordt uitsluitend uit de inhoud gedetecteerd, nooit uit de bestandsnaam.

**Podcasts (LibraryType PODCAST):** het eerste feed-gebaseerde librarytype — geen library-directory
op disk. `subscribePodcast(feedUrl)` (of de uurlijkse `PodcastRefreshScheduler`, met
`lastRefreshedAt`-guard tegen dubbele sweeps op meerdere nodes) stuurt `PODCAST_REFRESH_REQUESTED`
(globale queue). De worker haalt de RSS-feed op (conditional GET met ETag/Last-Modified, cap 500
items), synct kanaal-metadata/cover en maakt `PodcastEpisodeEntity`-rijen (dedup op guid). De
nieuwste N (default 3) krijgen `PODCAST_EPISODE_DOWNLOAD_REQUESTED` op de cache-dir-queue van de
node die de refresh deed; de disk-handler downloadt de enclosure (volgt redirects) naar
`{cache}/podcasts/` en stuurt `AUDIO_FILE_FOUND`, waarna afspelen identiek is aan tracks. Oudere
afleveringen downloaden on-demand via de `downloadPodcastEpisode`-mutation. Retentie: de dagelijkse
cache-cleanup verwijdert downloads ouder dan `podcast-retention-days` (default 30), behalve als
iemand middenin de aflevering zit — de afleverings-rij blijft en kan opnieuw downloaden.

---

## Flow 2: Library Analyseren (metadata ophalen)

**Trigger:** GraphQL mutation `analyzeLibrary()` in `ScannerController`

```mermaid
flowchart TD
    API([analyzeLibrary API]) -->|"per node"| A

    A["ANALYZE_LIBRARY_REQUESTED\n.{nodeName}"]
    A --> B["AnalyzeLibraryRequestedHandle\n📦 worker"]

    B -->|"per directory (incl. cache)"| C["UPDATE_IMAGES_REQUESTED\n.{dirName}"]
    B -->|"series zonder metadata"| D["SHOW_FOUND"]
    B -->|"afleveringen zonder metadata"| E["EPISODE_FOUND"]
    B -->|"films zonder metadata"| F["MOVIE_FOUND"]
    B -->|"artiesten zonder metadata"| G["PERSON_FOUND"]
    B -->|"albums zonder afbeelding"| H["ALBUM_FOUND"]
    B -->|"tracks zonder metadata"| I["AUDIO_FILE_FOUND\n.{dirName}"]

    C --> C1["HandleUpdateImagesRequested\n📦 disk\nBlurHash voor één chunk afbeeldingen"]
    C1 -->|"chunk vol: nog werk te doen\nafterId = laatste id"| C

    D --> D1["HandleShowFound\n📦 worker"]
    D1 -->|"TMDB: titel, beschrijving\nposter + achtergrond downloaden"| IMG1["IMAGE_FOUND\n.{dirName}"]
    D1 -->|"TMDB aggregate credits:\ncast → persons + credits"| CR1["credit_entity\n(direct in DB)"]

    E --> E1["HandleEpisodeFound\n📦 worker"]
    E1 -->|"TMDB: afleveringsinfo\nafbeeldingen downloaden"| IMG2["IMAGE_FOUND\n.{dirName}"]
    E1 -->|"TMDB episode credits:\ncast + guest stars → persons + credits"| CR2["credit_entity\n(direct in DB)"]

    F --> F1["MovieFoundHandle\n📦 worker"]
    F1 -->|"TMDB: filminfo\nafbeeldingen downloaden"| IMG3["IMAGE_FOUND\n.{dirName}"]
    F1 -->|"TMDB movie credits:\ncast → persons + credits"| CR3["credit_entity\n(direct in DB)"]

    G --> G1["HandlePersonFound\n📦 worker\ncheckt bestaande afbeeldingen"]
    G --> G2["HandlePersonFound\n📦 disk"]
    G2 -->|"zoekt artist.nfo"| NFO1["NFO_FILE_FOUND\n.{dirName}"]

    H --> H1["HandleAlbumFound\n📦 worker"]
    H1 -->|"MusicBrainz API\nalbumhoes downloaden"| IMG4["IMAGE_FOUND\n.{dirName}"]
    H --> H2["HandleAlbumFound\n📦 disk"]
    H2 -->|"zoekt album.nfo"| NFO2["NFO_FILE_FOUND\n.{dirName}"]

    I --> I1["HandleAudioFileFound\n📦 disk\n(zelfde als Flow 1)"]

    IMG1 & IMG2 & IMG3 & IMG4 --> FIN["HandleImageFound\n📦 disk\nrij opslaan, géén BlurHash"]
    NFO1 & NFO2 --> FIN2["HandleNfoFileFound\n📦 disk\nXML parsen + opslaan"]
```

### BlurHash-sweep

`HandleImageFound` slaat een afbeelding snel op zonder BlurHash: dat coderen is CPU-duur en maakte
die handler de bottleneck bij grote scans. De hashes worden achteraf gevuld door de
`UPDATE_IMAGES_REQUESTED`-sweep, per directory — de **cache-directory inbegrepen**, want daar staat
de gedownloade artwork en dus de overgrote meerderheid van de afbeeldingen.

Die sweep verwerkt per bericht hoogstens `app.ister.server.blur-hash.chunk-size` afbeeldingen en
publiceert daarna een opvolger met een keyset-cursor (`afterId`). Eén sweep over de hele bibliotheek
in één bericht duurde langer dan RabbitMQ's `consumer_timeout` (30 min), waarna het bericht
teruggezet werd en de sweep eindeloos opnieuw begon zonder ooit te committen.

Twee subtiliteiten:

- De cursor is een **keyset op `id`**, geen offset en geen "eerstvolgende zonder hash". Een
  afbeelding die nooit te hashen is (een CMYK-JPEG die `ImageIO` niet leest) houdt `blur_hash NULL`;
  met een simpele `LIMIT` zou de sweep zulke rijen elke ronde opnieuw pakken en nooit eindigen.
  PostgreSQL sorteert `uuid` unsigned terwijl `java.util.UUID.compareTo` signed vergelijkt, dus zowel
  de `ORDER BY` als de `id >` moeten in de database gebeuren.
- De opvolger wordt pas gepubliceerd **nadat** de chunk gecommit is (zie `BlurHashChunkProcessor`).
  Andersom zou een mislukte commit een cursor achterlaten die voorbij nooit-opgeslagen werk staat.

---

## Flow 3: Heranalyse van specifiek item

**Trigger:** GraphQL-aanroepen zoals `analyzeShow(id)`, `analyzeMovie(id)`, `analyzeEpisode(id)`, etc.

```mermaid
flowchart TD
    API([analyzeItem API]) --> A

    A["ANALYZE_DATA\nglobaal of .{dirName}"]

    A --> W["AnalyzeDataHandle\n📦 worker"]
    A --> D["HandleAnalyzeDataDisk\n📦 disk"]

    W -->|Library| W1["cascade naar alle\nshows / films / artiesten"]
    W -->|Show| W2["wist metadata\nSHOW_FOUND\n+ cascade naar afleveringen"]
    W -->|Episode| W3["wist metadata +\nafbeeldingen + streams\nEPISODE_FOUND\n+ ANALYZE_DATA.{dirName}"]
    W -->|Movie| W4["wist metadata +\nafbeeldingen + streams\nMOVIE_FOUND\n+ ANALYZE_DATA.{dirName}"]
    W -->|Artist| W5["PERSON_FOUND\n+ cascade naar albums"]
    W -->|Album| W6["ALBUM_FOUND\n+ cascade naar tracks"]
    W -->|Track| W7["AUDIO_FILE_FOUND.{dirName}"]

    D -->|"wist HLS-cache"| D1["MEDIA_FILE_FOUND\nof AUDIO_FILE_FOUND"]
    D --> D2["NFO_FILE_FOUND"]
    D --> D3["SUBTITLE_FILE_FOUND"]

    W2 & W3 & W4 & W5 & W6 & W7 -->|"verdere verwerking"| FLOWS["→ Flow 2 handlers\n(TMDB / MusicBrainz / disk)"]
    D1 & D2 & D3 -->|"verdere verwerking"| FLOWS2["→ Flow 1 handlers\n(ffprobe / NFO / subtitels)"]
```

---

## Flow 4: Transcoding

**Trigger A:** Pre-transcode periodieke taak
**Trigger B:** Playback-verzoek van client
**Trigger C:** Playqueue-prefetch — `updatePlayQueue` (GraphQL) meldt voortgang; vlak voor het einde van het huidige item stuurt `PlayQueuePrefetchService` (📦 core) een `TRANSCODE_REQUESTED` (preTranscode=true) voor de volgende item(s), in de door de client gerapporteerde streamSettings.

```mermaid
flowchart TD
    TrigA([Periodieke taak]) -->|"per disk"| A
    TrigB([Playback verzoek]) -->|direct| B
    TrigC([updatePlayQueue voortgang]) -->|"einde nadert →\nvolgende item(s), keepUntil=+24h"| B

    A["PRE_TRANSCODE_RECENTLY_WATCHED\n.{diskName}"]
    A --> PA["HandlePreTranscodeRecentlyWatched\n📦 disk"]
    PA -->|"half-bekeken + volgende episodes\nkeepUntil=+30min (schuift elke run op)"| B
    PA -->|"bestand zonder geanalyseerde\nstreams → her-analyse"| MFF["MEDIA_FILE_FOUND\n.{dirName}"]

    B["TRANSCODE_REQUESTED\n.{dirName}  preTranscode=true/false\nkeepUntilEpochMillis (optioneel)"]
    B --> PB["HandleTranscodeRequested\n📦 transcoder"]
    PB -->|"HLS master playlist\nHLS variant playlists\nkeep_until in cache-dir"| FS1[("HLS bestanden\nop schijf")]
    PB -->|"per bitrate/pass\nbackground=true bij pre-transcode"| C

    C["TRANSCODE_PASS_REQUESTED\n.{dirName}"]
    C --> PC["HandleTranscodePassRequested\n📦 transcoder"]
    PC -->|"FFmpeg via Jaffree\nHLS segmenten genereren"| FS2[("HLS segmenten\nop schijf")]
```

**Prioriteit:** passes met `background=true` (pre-transcode/prefetch) draaien alleen op restcapaciteit
(`max-background-files`, `max-background-passes`) en worden gepreëmpt (FFmpeg gestopt, event vervalt —
de scheduler/prefetch stuurt later opnieuw) zodra interactieve playback een slot of thread nodig heeft.
Daarnaast draait background-FFmpeg met OS-niceness (`background-nice`, default 10, 0 = uit) via een bij
startup gegenereerd wrapper-script, zodat het ook op CPU-niveau alleen ongebruikte cycles krijgt;
ontbreekt `nice`, dan valt het automatisch terug op normale prioriteit.
Een succesvolle pass schrijft een `done_<segmentPrefix>`-marker; alleen die marker (niet de aanwezigheid
van segmenten) laat een latere pre-transcode de pass overslaan.

**Retentie:** de cleanup-taak verwijdert een cache-dir pas als hij ≥2 uur onaangeraakt is én de
`keep_until`-deadline (hoogste ooit ontvangen `keepUntilEpochMillis`) verstreken is. Prefetch stuurt
+24 uur; de periodieke pre-transcode stuurt +30 min en ververst dat elke 15 min zolang de regel geldt.

---

## Flow 5: Zoeken (Typesense)

**Trigger:** entiteit-creatie of metadata-verrijking; volledige reindex via GraphQL mutation `reindexSearch()`.
De enabled-vlag (`app.ister.typesense.enabled`) wordt **op runtime** in de handlers gecheckt (disabled →
event geconsumeerd en genegeerd, zoals de TMDB-key-check). Geen bean-conditions: die worden bij de
GraalVM native-image build bevroren.

```mermaid
flowchart TD
    C["ScannerHelperService\n(getOrCreate* → *_FOUND)"] -->|"createXFoundEvent stuurt óók"| A
    E["Verrijkings-handlers\n(MetadataSave, NFO, audio-tags,\nMusicBrainz, TMDB-cast)"] -->|na metadata-save| A
    D["Track-delete\n(HandleAudioFileFound.reassignTrackNumber)"] -->|action=DELETE| A

    A["SEARCH_INDEX_REQUESTED\n(entityType, entityId, action)"]
    A --> H["HandleSearchIndexRequested\n📦 search"]
    H -->|"entity laden + mappen\nupsert/delete document"| TS[(Typesense\nalias 'media')]

    API([reindexSearch API]) --> R["SEARCH_REINDEX_REQUESTED"]
    R --> RH["HandleSearchReindexRequested\n📦 search"]
    RH -->|"nieuwe collection media_v<ts>\nalle entiteiten pagineren + importeren\nalias omzetten, oude collections droppen"| TS
```

**Regel:** elke plek die een doorzoekbare entiteit (movie/show/episode/person/album/track) verwijdert,
moet `serverEventService.createSearchDeleteEvent(...)` aanroepen. Vangnetten: de upsert-handler verwijdert
het document als de entiteit niet meer bestaat, en `reindexSearch` bouwt de index volledig opnieuw op.

---

## Volledig Event Overzicht

```mermaid
graph LR
    subgraph Triggers
        T1([scanLibrary API])
        T2([analyzeLibrary API])
        T3([analyzeItem API])
        T4([Periodieke taak])
        T5([Playback])
    end

    subgraph disk module
        H1[HandleNewDirectoriesScanRequested]
        H2[FileScanRequestedHandle]
        H3[HandleMediaFileFound]
        H4[HandleAudioFileFound]
        H5[HandleSubtitleFileFound]
        H6[HandleImageFound]
        H7[HandleNfoFileFound]
        H8[HandleUpdateImagesRequested]
        H9[HandleAnalyzeDataDisk]
        H10[HandlePreTranscodeRecentlyWatched]
        H11[HandlePersonFound]
        H12[HandleAlbumFound]
    end

    subgraph worker module
        W1[AnalyzeLibraryRequestedHandle]
        W2[AnalyzeDataHandle]
        W3[HandleShowFound]
        W4[HandleEpisodeFound]
        W5[MovieFoundHandle]
        W6[HandlePersonFound]
        W7[HandleAlbumFound]
    end

    subgraph transcoder module
        TR1[HandleTranscodeRequested]
        TR2[HandleTranscodePassRequested]
    end

    T1 --> H1 --> H2
    H2 --> H3 --> H6
    H2 --> H4 --> H6
    H2 --> H5
    H2 --> H6
    H2 --> H7

    T2 --> W1
    W1 --> H8
    W1 --> W3 --> H6
    W1 --> W4 --> H6
    W1 --> W5 --> H6
    W1 --> W6
    W1 --> W7 --> H6
    W1 --> H7
    W1 --> H11 --> H7
    W1 --> H12 --> H7

    T3 --> W2
    W2 --> W3 & W4 & W5 & W6 & W7
    W2 --> H9
    H9 --> H3 & H4 & H7 & H5

    T4 --> H10 --> TR1 --> TR2
    T5 --> TR1
```

---

## Queue Scoping

| Scope | Events |
|-------|--------|
| **Node** `.{nodeName}` | `ANALYZE_LIBRARY_REQUESTED` |
| **Directory** `.{dirName}` | `NEW_DIRECTORIES_SCAN_REQUESTED`, `FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `EPUB_FILE_FOUND`, `SUBTITLE_FILE_FOUND`, `IMAGE_FOUND`, `NFO_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA` (disk), `PRE_TRANSCODE_RECENTLY_WATCHED`, `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED` |
| **Globaal** | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `BOOK_FOUND`, `CHAPTER_FOUND` (geen consumer), `PODCAST_FOUND` (geen consumer), `PODCAST_EPISODE_FOUND` (geen consumer), `PODCAST_REFRESH_REQUESTED`, `ANALYZE_DATA` (worker), `SEARCH_INDEX_REQUESTED`, `SEARCH_REINDEX_REQUESTED` |
| **Cache-directory** `.{nodeName}-cache-directory` | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (de download landt op de disk van die node) |

---

## Handler Referentie

| Handler | Module | Ontvangt | Verstuurt |
|---------|--------|----------|-----------|
| `HandleNewDirectoriesScanRequested` | disk | `NEW_DIRECTORIES_SCAN_REQUESTED` | `FILE_SCAN_REQUESTED` |
| `FileScanRequestedHandle` | disk | `FILE_SCAN_REQUESTED` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `IMAGE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandleMediaFileFound` | disk | `MEDIA_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleAudioFileFound` | disk | `AUDIO_FILE_FOUND` | `IMAGE_FOUND` (track- óf chapter-gebonden, per library-type) |
| `HandleEpubFileFound` | disk | `EPUB_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleSubtitleFileFound` | disk | `SUBTITLE_FILE_FOUND` | — |
| `HandleImageFound` | disk | `IMAGE_FOUND` | — |
| `HandleNfoFileFound` | disk | `NFO_FILE_FOUND` | — |
| `HandleUpdateImagesRequested` | disk | `UPDATE_IMAGES_REQUESTED` | `UPDATE_IMAGES_REQUESTED` (volgende chunk) |
| `HandleAnalyzeDataDisk` | disk | `ANALYZE_DATA` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandlePreTranscodeRecentlyWatched` | disk | `PRE_TRANSCODE_RECENTLY_WATCHED` | `TRANSCODE_REQUESTED`, `MEDIA_FILE_FOUND` (voor bestanden zonder geanalyseerde streams) |
| `HandlePersonFound` | disk | `PERSON_FOUND` | `NFO_FILE_FOUND` |
| `HandleAlbumFound` | disk | `ALBUM_FOUND` | `NFO_FILE_FOUND` |
| `AnalyzeLibraryRequestedHandle` | worker | `ANALYZE_LIBRARY_REQUESTED` | `UPDATE_IMAGES_REQUESTED`, `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `AUDIO_FILE_FOUND` |
| `AnalyzeDataHandle` | worker | `ANALYZE_DATA` | cascade per entiteitstype |
| `HandleShowFound` | worker | `SHOW_FOUND` | `IMAGE_FOUND` (+ cast credits direct in DB) |
| `HandleEpisodeFound` | worker | `EPISODE_FOUND` | `IMAGE_FOUND` (+ cast/guest star credits direct in DB) |
| `MovieFoundHandle` | worker | `MOVIE_FOUND` | `IMAGE_FOUND` (+ cast credits direct in DB) |
| `HandlePersonFound` | worker | `PERSON_FOUND` | — |
| `HandleAlbumFound` | worker | `ALBUM_FOUND` | `IMAGE_FOUND` |
| `HandleBookFound` | worker | `BOOK_FOUND` | `IMAGE_FOUND` (Open Library-cover, alleen als er nog geen is) |
| `HandlePodcastRefreshRequested` | worker | `PODCAST_REFRESH_REQUESTED` | `IMAGE_FOUND` (feed-cover), `PODCAST_EPISODE_FOUND`, `PODCAST_EPISODE_DOWNLOAD_REQUESTED` (nieuwste N) |
| `HandlePodcastEpisodeDownloadRequested` | disk | `PODCAST_EPISODE_DOWNLOAD_REQUESTED` | `AUDIO_FILE_FOUND` (op de cache-dir-queue → ffprobe + HLS-pregeneratie) |
| `HandleTranscodeRequested` | transcoder | `TRANSCODE_REQUESTED` | `TRANSCODE_PASS_REQUESTED` |
| `HandleTranscodePassRequested` | transcoder | `TRANSCODE_PASS_REQUESTED` | — |
| `HandleSearchIndexRequested` | search | `SEARCH_INDEX_REQUESTED` | — (upsert/delete in Typesense) |
| `HandleSearchReindexRequested` | search | `SEARCH_REINDEX_REQUESTED` | — (volledige rebuild + alias-swap) |

`SEARCH_INDEX_REQUESTED` wordt verstuurd door: `ServerEventService.createXFoundEvent` (alle zes, bij creatie),
`MetadataSave` (worker, TMDB), `HandlePersonFound`/`HandleAlbumFound` (worker, MusicBrainz),
`PersonLookupService` (worker, TMDB-cast), `HandleNfoFileFound`, `HandleAudioFileFound` (incl. DELETE bij
track-dedup) en `HandlePersonFound`/`HandleAlbumFound` (disk, na metadata-delete).
