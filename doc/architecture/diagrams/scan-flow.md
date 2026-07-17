# Library scan flow

Triggered by the GraphQL mutation `scanLibrary()` (`ScannerController`). The directory walk fans
out into one `FILE_SCAN_REQUESTED` per file, which routes on extension to the type-specific
handlers.

```mermaid
flowchart TD
    API([scanLibrary API]) -->|sends| A

    A["NEW_DIRECTORIES_SCAN_REQUEST\n.{dirName}"]
    A --> B["HandleNewDirectoriesScanRequested\n📦 disk"]
    B -->|walks the filesystem| C

    C["FILE_SCAN_REQUESTED\n.{dirName} — per file"]
    C --> D["FileScanRequestedHandle\n📦 disk\nroutes on extension"]

    D -->|video| E["MEDIA_FILE_FOUND\n.{dirName}"]
    D -->|audio| F["AUDIO_FILE_FOUND\n.{dirName}"]
    D -->|".epub (BOOK library)"| EP["EPUB_FILE_FOUND\n.{dirName}"]
    D -->|".cbz/.pdf (COMIC library)"| CO["COMIC_FILE_FOUND\n.{dirName}"]
    D -->|.srt| G["SUBTITLE_FILE_FOUND\n.{dirName}"]
    D -->|image| H["IMAGE_FOUND\n.{dirName}"]
    D -->|.nfo| I["NFO_FILE_FOUND\n.{dirName}"]

    E --> J["HandleMediaFileFound\n📦 disk"]
    J -->|"ffprobe: streams + duration\nembedded subs → SRT\nscreenshot → background"| K["IMAGE_FOUND\n.{dirName}"]

    F --> L["HandleAudioFileFound\n📦 disk"]
    L -->|"ffprobe: streams + duration\nID3 tags: title, artist, track no.\nembedded album cover\nclear HLS cache"| M["IMAGE_FOUND\n.{dirName}"]

    G --> N["HandleSubtitleFileFound\n📦 disk"]
    N -->|"links SRT to episode\ncreates MediaFileStreamEntity\nEXTERNAL_SUBTITLE"| DB1[(Database)]

    K --> O["HandleImageFound\n📦 disk"]
    M --> O
    H --> O
    O -->|"generate BlurHash\nsave ImageEntity\nlink to show/movie/episode/etc."| DB2[(Database)]

    I --> P["HandleNfoFileFound\n📦 disk"]
    P -->|"parses XML NFO\ntitle, description, release date\nbiography/review for music/books"| DB3[(Database)]

    EP --> Q["HandleEpubFileFound\n📦 disk"]
    Q -->|"reads OPF: title, language, description\nmedia overlays from content (SMIL)\ncover from the zip into the cache"| R["IMAGE_FOUND\n.{cacheDirName}"]
    R --> O

    CO --> S["HandleComicFileFound\n📦 disk"]
    S -->|"CBZ/PDF: page count + cover\nComicInfo.xml refinement\ncover into the cache"| T["IMAGE_FOUND\n.{cacheDirName}"]
    T --> O
```
