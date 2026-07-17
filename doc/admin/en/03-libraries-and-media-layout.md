# Libraries and media layout

A **library** is a named collection of one type (`MOVIE`, `SHOW`, `MUSIC`, `BOOK`, `COMIC` or
`PODCAST`). A **directory** is a path on disk attached to a library; a library can span several
directories, even across nodes. The scanner decides what a file is from its **path**, so the
on-disk layout matters.

## Configuring libraries and directories

Indexed properties, from `disk/src/main/resources/disk.properties` (as env vars:
`APP_ISTER_DISK_LIBRARIES_0_NAME` etc.):

```properties
app.ister.disk.libraries[0].name=shows
app.ister.disk.libraries[0].type=SHOW
app.ister.disk.libraries[1].name=books
app.ister.disk.libraries[1].type=BOOK

app.ister.disk.directories[0].name=disk1
app.ister.disk.directories[0].path=/disk1
app.ister.disk.directories[0].library=shows
app.ister.disk.directories[1].name=disk2
app.ister.disk.directories[1].path=/disk2
app.ister.disk.directories[1].library=shows
```

Directory **names must be unique across the whole cluster** — they name the per-directory work
queues ([Multi-node](04-multi-node.md)). Write paths **without a trailing slash** and keep them
stable: the path is stored verbatim in the database and compared as a string prefix, so changing
`/disk1` to `/disk1/` later counts as a path change. Rows are created/updated from this config at
every startup.

## Expected layout per type

This is the short version; [chapter 7](07-naming-conventions.md) is the full naming reference
(exact patterns, accepted extensions, special files, and common mistakes).

**Shows** — `Show Name (year)/Season NN/sNNeNN.mkv`:

```
The Wire (2002)/Season 01/s01e01.mkv
```

**Movies** — one file (or a folder) per movie, name ending in the year:

```
Heat (1995)/Heat (1995).mkv
```

**Music** — `Artist/Album/track`:

```
Miles Davis/Kind of Blue/01 So What.flac
```

**Books** — one logical book per author, in two interchangeable forms that converge on the same
book entry: an epub directly under the author, and/or an audiobook folder of numbered chapters:

```
Terry Pratchett/Guards! Guards!.epub
Terry Pratchett/Guards! Guards!/001_Chapter 1.mp3
```

Read-aloud (EPUB 3 media-overlay) epubs are detected automatically from the epub's **contents**,
never from the filename.

**Comics** — series-first: `{Series Name (optional year)}/Volume 27.cbz`. Also `.pdf` and
`.epub`; loose patterns like `attackontitan_vol27.pdf`, `series_issue8.pdf` and `name#3.cbz`
are tolerated.

Video containers recognised: `mkv`, `mp4`; subtitles: `.srt` next to the video (image subtitles
inside mkv are extracted and OCR'd); local artwork: `jpg`/`png`; `.nfo` files are read for
metadata hints.

## Podcasts

A `PODCAST` library needs **no directory at all** — it is feed-based:

- Subscribe from the client (or the GraphQL `subscribePodcast(feedUrl)` mutation); the directory
  search in the client uses the free iTunes Search API.
- Feeds refresh **hourly**; the newest episodes (default 3, `auto-download-count`) are downloaded
  automatically into the cache directory, older ones on demand when a user plays them.
- Downloads expire after 30 days (`podcast-retention-days`) unless someone is mid-episode.

## Scanning and analyzing

Two GraphQL mutations (also exposed in the client's admin screens):

- **`scanLibrary`** — walks the directories, registers new/changed files, and kicks off metadata
  fetching for anything new. Run it after adding media; there is no filesystem watcher.
- **`analyzeLibrary`** — re-processes what is already known: probes media files for streams,
  regenerates derived data, and backfills metadata that earlier scans missed (for example after
  you add a TMDB key or a language). It does not discover new files.

Both are asynchronous — they queue events and return immediately; progress is visible in the
client's activity view. Details of the pipeline are in the
[architecture documentation](../../architecture/en/02-scanning-and-analysis.md).

## Where to next

- [Multi-node](04-multi-node.md) — directories spread over several servers
- [Maintenance](06-maintenance-and-troubleshooting.md) — what happens to caches over time
