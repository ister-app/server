# Scanning and analysis

Two distinct flows populate the database: **scanning** registers what is on disk, **analyzing**
enriches it with metadata from external providers. Both are triggered from `ScannerController`
(GraphQL mutations `scanLibrary()` / `analyzeLibrary()`), plus per-item reanalysis mutations.

## Startup bootstrap

`disk/.../StartupTasks` handles Spring's `ContextRefreshedEvent` — no RabbitMQ events are sent at
startup. It creates or updates `NodeEntity`, `LibraryEntity` and `DirectoryEntity` rows from the
configuration properties (`disk.properties` / env vars), creates the cache directories on disk, and
validates the multi-node configuration. See the [startup diagram](../diagrams/startup.md).

## Library scan

See the [scan-flow diagram](../diagrams/scan-flow.md). `scanLibrary()` sends
`NEW_DIRECTORIES_SCAN_REQUEST` per directory; the disk handler walks the filesystem and emits one
`FILE_SCAN_REQUESTED` per file. `FileScanRequestedHandle` routes on extension (and library type):

| File | Event | Handler work |
| --- | --- | --- |
| Video | `MEDIA_FILE_FOUND` | ffprobe streams + duration, extract embedded subs to SRT, screenshot as backdrop |
| Audio | `AUDIO_FILE_FOUND` | ffprobe, ID3 tags (title/artist/track no), embedded cover, clear the HLS cache |
| `.epub` (BOOK library) | `EPUB_FILE_FOUND` | OPF title/language/description, media overlays from content, cover from the zip |
| `.cbz`/`.pdf`/`.epub` (COMIC library) | `COMIC_FILE_FOUND` (epubs reuse `EPUB_FILE_FOUND`) | page count, `ComicInfo.xml`, cover extraction |
| `.srt` | `SUBTITLE_FILE_FOUND` | link SRT to episode as an `EXTERNAL_SUBTITLE` stream |
| Image | `IMAGE_FOUND` | save `ImageEntity`, link to show/movie/episode/etc. |
| `.nfo` | `NFO_FILE_FOUND` | parse XML: title, description, release date, biography/review |

Entity creation goes through `ScannerHelperService.getOrCreate*`, which also fires the `*_FOUND`
enrichment events and the search-index creation events.

## Library analyze

See the [analyze-flow diagram](../diagrams/analyze-flow.md). `analyzeLibrary()` sends
`ANALYZE_LIBRARY_REQUEST` per node; the worker finds everything **missing** metadata or images and
fans out: `SHOW_FOUND` / `EPISODE_FOUND` / `MOVIE_FOUND` (TMDB), `PERSON_FOUND` / `ALBUM_FOUND`
(MusicBrainz + NFO lookup on the disk side), `AUDIO_FILE_FOUND` for tracks without metadata, and
`UPDATE_IMAGES_REQUESTED` per directory for the BlurHash sweep. The per-type pipelines are covered
in [chapter 3](03-media-types-and-metadata.md).

## Per-item reanalysis

Mutations like `analyzeShow(id)` and `analyzeMovie(id)` send `ANALYZE_DATA`, consumed by **two**
handlers: `AnalyzeDataHandle` (worker) wipes the item's metadata/images/streams and cascades — a
library fans out to all its shows/movies/artists, a show to its episodes, an album to its tracks —
re-firing the `*_FOUND` events; `HandleAnalyzeDataDisk` (disk) clears the HLS cache and re-emits the
file-level events (`MEDIA_FILE_FOUND`/`AUDIO_FILE_FOUND`, `NFO_FILE_FOUND`, `SUBTITLE_FILE_FOUND`).

## The BlurHash sweep

`HandleImageFound` deliberately saves images **without** a BlurHash: encoding one is CPU-expensive
and made that handler the bottleneck of large scans. The hashes are filled afterwards by the
`UPDATE_IMAGES_REQUESTED` sweep, per directory — **including the cache directory**, which holds the
downloaded artwork and therefore the vast majority of images.

Each message processes at most `app.ister.server.blur-hash.chunk-size` images, then publishes a
successor message carrying a keyset cursor (`afterId`). One sweep over a whole library in a single
message used to exceed RabbitMQ's `consumer_timeout` (30 minutes), so the message was requeued and
the sweep restarted endlessly without ever committing.

Two subtleties:

- The cursor is a **keyset on `id`** — not an offset and not "next row without a hash". An image
  that can never be hashed (a CMYK JPEG that `ImageIO` cannot read) keeps `blur_hash NULL`; a naive
  `LIMIT` query would re-select such rows every round and never terminate. PostgreSQL orders `uuid`
  unsigned while `java.util.UUID.compareTo` compares signed, so both the `ORDER BY` and the `id >`
  comparison must run **in the database**, never in Java.
- The successor message is published only **after** the chunk's transaction commits
  (`BlurHashChunkProcessor`). The other order would let a failed commit leave a cursor pointing past
  work that was never saved.

## Related scheduled jobs

`CacheCleanupScheduler` (disk) and `TmpTranscodeCleanupScheduler` (transcoder) run a daily zombie
sweep of the image cache and transcode tmp dirs, deleting files no database row references, and
expire old podcast downloads. **`app.ister.server.cache-cleanup.dry-run` defaults to `true`** — the
cleanup only logs until that flag is switched off.


