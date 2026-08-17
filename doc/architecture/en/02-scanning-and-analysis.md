---
description: How Ister scans media libraries and analyzes them for metadata, from startup bootstrap and file-type routing to per-item reanalysis and the BlurHash sweep.
---

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
| Audio | `AUDIO_FILE_FOUND` | ffprobe, ID3 tags (title/track no, track artist from the `artist` tag with the path artist as fallback), embedded cover, clear the HLS cache |
| `.epub` (BOOK library) | `EPUB_FILE_FOUND` | OPF title/language/description, media overlays from content, cover from the zip |
| `.cbz`/`.pdf`/`.epub` (COMIC library) | `COMIC_FILE_FOUND` (epubs reuse `EPUB_FILE_FOUND`) | page count, `ComicInfo.xml`, cover extraction |
| `.srt` | `SUBTITLE_FILE_FOUND` | link SRT to episode as an `EXTERNAL_SUBTITLE` stream |
| Image | `IMAGE_FOUND` | save `ImageEntity`, link to show/movie/episode/etc. |
| `.nfo` | `NFO_FILE_FOUND` | parse XML: title, description, release date, biography/review |

Entity creation goes through `ScannerHelperService.getOrCreate*`, which also fires the `*_FOUND`
enrichment events and the search-index creation events.

### Multi-episode files

A filename may carry an episode **range** — `s04e06-e07.mkv`, `s04e06-08.mkv`, `s04e06e07.mkv` —
for a file holding up to three consecutive episodes. `PathObject` parses the range (an implausible
range, backwards or longer than three, falls back to the first episode) and `MediaFileScanner`
creates one `EpisodeEntity` per episode, so each gets its own TMDB metadata and watch status. The
file's `episode_entity_id` FK always points at the **first** episode; every contained episode
(including the first) additionally gets a `media_file_episode_entity` link row carrying its
`start`/`duration` slice within the file. No link rows means "normal single-episode file" — all
existing FK-based queries stay correct, and playback paths resolve files via
`MediaFileEpisodeService.filesForEpisode`.

The slice boundaries are computed in `HandleMediaFileFound` (`MediaFileFoundEpisodeBoundaries`)
once ffprobe knows the file duration: one MKV chapter per episode is used directly; with more
chapters (scene markers) the chapter nearest to each equal-split point wins, unless that would make
an episode implausibly short; otherwise the duration is split equally. Each episode also gets its
own backdrop still, taken at the midpoint of its slice. Files scanned **before** multi-episode
support are backfilled by a normal library rescan: the scanner notices an existing file whose path
parses as a range but has no link rows, creates the missing episodes and links, and re-sends
`MEDIA_FILE_FOUND` so the boundaries and stills get computed.

Sidecar files (NFO, local images, external subtitles) attach to the first episode of the range,
as before.

### Intro/outro detection

Recurring intros and closing credits are found by **comparing audio across a season**: the disk
module decodes short windows (first 10 / last 4 minutes of each episode's slice) to mono PCM,
fingerprints them (`ChromaFingerprinter`, a chromaprint-style 32-bit gradient hash per 128 ms —
loudness-invariant, no external library), and `SegmentMatcher` finds the longest shared run
between an episode and up to four season neighbours. The median bounds over agreeing pairs (at
least two, one for two-episode seasons) become `media_file_segment_entity` rows (`INTRO`/`OUTRO`)
in **absolute file time**; in a multi-episode file each slice gets its own rows, disambiguated by
`episode_entity_id`. The player reads them as `MediaFile.segments` for its skip-intro /
next-episode buttons.

Because detection is cross-episode it cannot run inside the per-file `MEDIA_FILE_FOUND` handler:
that handler instead fires a season-scoped `DETECT_SEGMENTS` event **after its transaction
commits**, on the same directory-scoped queue family, so detection runs on the node owning the
files. `HandleDetectSegments` is idempotent — files whose `media_file_entity.segment_detector_version`
already equals the current detector version are only used as comparison material — so one event
per analyzed episode is fine: the last episode's event does the real work. The version column is
also the "ran but found nothing" sentinel; `null` means detection never ran, and the scanner's
backfill (`app.ister.server.segment-detect-backfill`, default on, once per season per run) sends
`DETECT_SEGMENTS` for such files on a rescan. Per-item reanalysis wipes the segment rows and
resets the version, and bumping `SegmentDetectionChunkProcessor.DETECTOR_VERSION` re-runs
detection everywhere via the same backfill. Known limitation: a season spread over multiple nodes
only pairs the episodes local to each node — a node holding a single stray episode detects nothing
for it.

That idempotence is serial, not concurrent. Recomputing a file is a delete-then-insert, and two
transactions doing it for the same season neither see nor cancel each other's rows, so a
re-analysis sweep — one message per file, all for the same few seasons — stored every segment once
per consumer. A chunk therefore claims its season with a **non-blocking** advisory lock
(`pg_try_advisory_xact_lock`, namespace 1, key `hashtext(seasonId)`); a message that does not get
the claim is dropped rather than retried, because the holder's chunk chain covers the whole season
anyway. Blocking instead would idle a listener thread for the minutes a season takes and walk into
the same `consumer_timeout`. A unique index on (file, type, episode) with `NULLS NOT DISTINCT`
(V39, which also de-duplicated the existing rows) is the backstop.

One message detects at most `app.ister.server.segment-detect.chunk-size` episodes (default 4):
fingerprinting a whole season in one go can outlast RabbitMQ's `consumer_timeout` (default
30 minutes), which closes the channel and requeues the message forever. Like the blur-hash sweep,
`SegmentDetectionChunkProcessor` runs one chunk in its own transaction (stretched so slices of one
multi-episode file never straddle a chunk boundary), and `HandleDetectSegments` publishes a
successor message for the same season only after that commit. The version column is the cursor,
and it is stamped even when decoding fails, so the chain always terminates.

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

The `*_FOUND` events are published **after the wipe has committed**
(`AfterCommitPublisher.publishAfterCommit`): their consumers check for existing metadata/image rows
and would otherwise still see the doomed rows and skip the refetch, leaving the item permanently
without covers. For albums, disk-side `HandleAlbumFound` additionally re-emits `FILE_SCAN_REQUESTED`
for local artwork (`cover.jpg` and friends) in the album directory — album analysis wipes those image
rows too, and unlike movies/episodes no directory rescan follows, so the files are re-ingested
explicitly (deduped by `ImageScanner` on the existing `(directory, path)` row).

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


