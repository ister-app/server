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
    P -->|"Parseert XML NFO\ntitel, beschrijving, releasedatum\nbiografie/review voor muziek"| DB3[(Database)]
```

---

## Flow 2: Library Analyseren (metadata ophalen)

**Trigger:** GraphQL mutation `analyzeLibrary()` in `ScannerController`

```mermaid
flowchart TD
    API([analyzeLibrary API]) -->|"per node"| A

    A["ANALYZE_LIBRARY_REQUESTED\n.{nodeName}"]
    A --> B["AnalyzeLibraryRequestedHandle\n📦 worker"]

    B -->|"per directory"| C["UPDATE_IMAGES_REQUESTED\n.{dirName}"]
    B -->|"series zonder metadata"| D["SHOW_FOUND"]
    B -->|"afleveringen zonder metadata"| E["EPISODE_FOUND"]
    B -->|"films zonder metadata"| F["MOVIE_FOUND"]
    B -->|"artiesten zonder metadata"| G["PERSON_FOUND"]
    B -->|"albums zonder afbeelding"| H["ALBUM_FOUND"]
    B -->|"tracks zonder metadata"| I["AUDIO_FILE_FOUND\n.{dirName}"]

    C --> C1["HandleUpdateImagesRequested\n📦 disk\nBlurHash voor afbeeldingen zonder hash"]

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

    IMG1 & IMG2 & IMG3 & IMG4 --> FIN["HandleImageFound\n📦 disk\nBlurHash + opslaan"]
    NFO1 & NFO2 --> FIN2["HandleNfoFileFound\n📦 disk\nXML parsen + opslaan"]
```

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

```mermaid
flowchart TD
    TrigA([Periodieke taak]) -->|"per disk"| A
    TrigB([Playback verzoek]) -->|direct| B

    A["PRE_TRANSCODE_RECENTLY_WATCHED\n.{diskName}"]
    A --> PA["HandlePreTranscodeRecentlyWatched\n📦 disk"]
    PA -->|"verzamelt recent bekeken\nbijwerken keep-bestand"| B
    PA -->|"bestand zonder geanalyseerde\nstreams → her-analyse"| MFF["MEDIA_FILE_FOUND\n.{dirName}"]

    B["TRANSCODE_REQUESTED\n.{dirName}  preTranscode=true/false"]
    B --> PB["HandleTranscodeRequested\n📦 transcoder"]
    PB -->|"HLS master playlist\nHLS variant playlists"| FS1[("HLS bestanden\nop schijf")]
    PB -->|"per bitrate/pass"| C

    C["TRANSCODE_PASS_REQUESTED\n.{dirName}"]
    C --> PC["HandleTranscodePassRequested\n📦 transcoder"]
    PC -->|"FFmpeg via Jaffree\nHLS segmenten genereren"| FS2[("HLS segmenten\nop schijf")]
```

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
| **Directory** `.{dirName}` | `NEW_DIRECTORIES_SCAN_REQUESTED`, `FILE_SCAN_REQUESTED`, `MEDIA_FILE_FOUND`, `AUDIO_FILE_FOUND`, `SUBTITLE_FILE_FOUND`, `IMAGE_FOUND`, `NFO_FILE_FOUND`, `UPDATE_IMAGES_REQUESTED`, `ANALYZE_DATA` (disk), `PRE_TRANSCODE_RECENTLY_WATCHED`, `TRANSCODE_REQUESTED`, `TRANSCODE_PASS_REQUESTED` |
| **Globaal** | `SHOW_FOUND`, `EPISODE_FOUND`, `MOVIE_FOUND`, `PERSON_FOUND`, `ALBUM_FOUND`, `ANALYZE_DATA` (worker), `SEARCH_INDEX_REQUESTED`, `SEARCH_REINDEX_REQUESTED` |

---

## Handler Referentie

| Handler | Module | Ontvangt | Verstuurt |
|---------|--------|----------|-----------|
| `HandleNewDirectoriesScanRequested` | disk | `NEW_DIRECTORIES_SCAN_REQUESTED` | `FILE_SCAN_REQUESTED` |
| `FileScanRequestedHandle` | disk | `FILE_SCAN_REQUESTED` | `MEDIA_FILE_FOUND` / `AUDIO_FILE_FOUND` / `IMAGE_FOUND` / `NFO_FILE_FOUND` / `SUBTITLE_FILE_FOUND` |
| `HandleMediaFileFound` | disk | `MEDIA_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleAudioFileFound` | disk | `AUDIO_FILE_FOUND` | `IMAGE_FOUND` |
| `HandleSubtitleFileFound` | disk | `SUBTITLE_FILE_FOUND` | — |
| `HandleImageFound` | disk | `IMAGE_FOUND` | — |
| `HandleNfoFileFound` | disk | `NFO_FILE_FOUND` | — |
| `HandleUpdateImagesRequested` | disk | `UPDATE_IMAGES_REQUESTED` | — |
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
| `HandleTranscodeRequested` | transcoder | `TRANSCODE_REQUESTED` | `TRANSCODE_PASS_REQUESTED` |
| `HandleTranscodePassRequested` | transcoder | `TRANSCODE_PASS_REQUESTED` | — |
| `HandleSearchIndexRequested` | search | `SEARCH_INDEX_REQUESTED` | — (upsert/delete in Typesense) |
| `HandleSearchReindexRequested` | search | `SEARCH_REINDEX_REQUESTED` | — (volledige rebuild + alias-swap) |

`SEARCH_INDEX_REQUESTED` wordt verstuurd door: `ServerEventService.createXFoundEvent` (alle zes, bij creatie),
`MetadataSave` (worker, TMDB), `HandlePersonFound`/`HandleAlbumFound` (worker, MusicBrainz),
`PersonLookupService` (worker, TMDB-cast), `HandleNfoFileFound`, `HandleAudioFileFound` (incl. DELETE bij
track-dedup) en `HandlePersonFound`/`HandleAlbumFound` (disk, na metadata-delete).
