---
description: Full naming reference for Ister media libraries, with the exact directory and filename patterns for shows, movies, music, books and comics.
---

# Naming conventions per library type

The scanner derives everything from paths and filenames, so getting the names right is what makes
a library "just work". [Chapter 4](04-libraries-and-media-layout.md) gives the short version; this
page is the full reference of what the parsers actually accept, per library type. Files and
directories that match no rule are **silently ignored** — a title that never shows up is almost
always a naming problem.

Rules that apply everywhere:

- A year suffix is always exactly **four digits in parentheses**: `(2019)`. `[2019]`, `2019` and
  `(19)` are not recognised.
- Extension matching is case-insensitive (`Cover.JPG` works).
- Directories whose name **starts** with a `.` are skipped entirely. Dots elsewhere in a name are
  fine — "J.K. Rowling", "R.E.M." and "Dr. Stone" all work.
- Artwork typing is by substring match on the filename (minus extension): a name containing
  `background` or `thumb` becomes the **backdrop**, one containing `cover`, `folder`, `poster` or
  `artist` becomes the **poster/cover** — and when a name contains both kinds of token, backdrop
  wins. Which of these names are accepted at which directory level differs per library type; see
  each section.

## Shows

```
{Show Name} ({year})/Season {N}/s{NN}e{NN}.mkv
```

```
The Wire (2002)/
  tvshow.nfo
  cover.png
  Season 01/
    s01e12.mkv
    s01e12.en.srt
    s01e12-thumb.jpg
  Season 04/
    s04e06-e07.mkv                # double episode: one file, two episodes
```

- The show directory **must** end in `(year)`; a season folder directly under the library root is
  ignored.
- Season folders are `Season {N}` (case-insensitive, `Season 1` and `season 01` both work).
- Episode files carry an `sNNeNN` token (1–4 digits each, case-insensitive): `s01e12.mkv`,
  `S05E05.mp4`. The show's title and year always come from the show directory, not the filename.
- The **season number in the filename wins over the folder**: `Season 02/s01e01.mkv` is filed
  under season 1.
- **Multi-episode files**: `s04e06-e07.mkv` (also written `s04e06e07` or `s04e06-07`) creates
  both episodes pointing at the one file, up to **three** episodes per file. An implausible range
  (backwards, or wider than three) is logged and treated as the single first episode.
- Video containers: `.mkv`, `.mp4`. Subtitles: `.srt` next to the episode, matched by filename
  prefix; a language code between the last two dots (`s01e01.en.srt`, `s01e01.nld.srt`) sets the
  subtitle language. Image-based subtitles embedded in the container are OCRed; untagged streams
  fall back to `app.ister.server.subtitle-ocr-default-language` (default `eng`).
- NFO files: `tvshow.nfo` at show level, `sNNeNN*.nfo` at episode level.
- Artwork: `.jpg`/`.png` whose name contains `cover`, `folder`, `poster` or `artist` (poster) or
  `background`/`thumb` (backdrop) — substring match, backdrop wins on mixed names.

## Movies

```
{Movie Name} ({year}).mkv
```

```
Heat (1995)/
  Heat (1995).mkv
  Heat (1995)-cover.jpg
```

- The **filename** must end in `(year)` before the extension — that is what distinguishes a movie
  from a stray video file. A wrapping folder is optional; `Movie (2024).mkv` directly in the
  library root works too.
- A suffix of letters/hyphens after the year is allowed and used for artwork, following the type
  rule above: `Heat (1995)-cover.jpg` (or `-poster`/`-folder`) becomes the poster,
  `Heat (1995)-thumb.jpg` (or `-background`) the backdrop. A bare `Heat (1995).jpg` carries
  **no** type token and is dropped.
- Same containers as shows (`.mkv`, `.mp4`). Movie-level `.nfo` files and subtitles are currently
  not picked up for movies.

## Music

```
{Artist}/{Album ({year})}/{NN} - {Track Title}.flac
```

```
The Beatles/
  artist.nfo
  artist.jpg
  Abbey Road (1969)/
    album.nfo
    cover.jpg
    01 - Come Together.flac
Grease_ Soundtrack (1991)/        # flat: no artist folder
  01-Grease.flac
```

- Optional `(year)` on the artist folder is the artist's birth year; on the album folder it is the
  release year.
- Track numbers come from the leading digits of the filename: `01 - Title`, `01. Title`,
  `01-Title` all work; `1-01 - Title` is read as disc 1, track 1. Without a leading number the
  ` - `-separated segments are scanned for one (`Artist - 20 - Title` → track 20), and failing
  that the track number is taken from the audio tags.
- Album folder names are normalized: a trailing format marker (` FLAC`, `(MP3)`, `[FLAC]`, …) and
  a leading `{Artist} - ` prefix are stripped, so `The Beatles - Abbey Road (1969) [FLAC]` and
  `Abbey Road (1969)` are the same album.
- A **flat album** directly under the library root (no artist folder) is allowed; the artist then
  comes from the `album_artist` tag in the files.
- Tags decide who performs what: `album_artist` identifies the album, `artist` the performer of the
  individual track — on a compilation that per-track tag is the only place the real artist exists.
  A `feat.`/`ft.`/`featuring` guest may stay in the `artist` tag: the server credits the primary
  artist and the guest separately, so the track shows up on both artist pages. An ampersand is left
  alone ("Simon & Garfunkel" stays one artist).
- Artist names are matched ignoring case and repeated spaces, so "ABBA" and "Abba" are one artist.
- Audio formats: `mp3`, `flac`, `aac`, `opus`, `ogg`, `wav`, `m4a`, `wma`.
- Special files: `artist.nfo` at artist level plus artist images whose name contains `artist`,
  `folder`, `background` or `thumb`; `album.nfo` at album level plus covers whose name contains
  `cover` or `folder`. Other `.nfo` names are ignored; typing follows the backdrop/poster rule
  above.

## Books

```
{Author}/{Book Name}.epub
{Author}/{Book Name ({year})}/{NNN}_{Chapter}.mp3
```

```
Terry Pratchett/
  artist.nfo
  Guards! Guards!.epub            # the epub…
  Guards! Guards!/                # …and the audiobook: same book
    album.nfo
    cover.jpg
    001_Chapter 1.mp3
    002_Chapter 2.mp3
```

- The epub under the author and the audiobook folder **converge on one book** when they share the
  same book name. A `(year)` on the author folder is the birth year, on the book it is the
  publication year.
- Chapter files start with 1–4 digits (`001_`, `01 - `, `12.`); the number only determines
  ordering and may start at 0. An epub may also live inside the book folder
  (`Author/Book/Book.epub`).
- A read-aloud edition can be named `{Book Name} (karaoke).epub` so it lands on the same book —
  but whether it actually *is* read-aloud (EPUB 3 media overlays) is detected from the epub's
  contents, never from the name. The ISBN is likewise read from inside the epub, not the filename.
- Audio formats: as music, plus `m4b`. Special files: `artist.nfo` at author level plus author
  images whose name contains `artist`, `folder`, `background` or `thumb`; `album.nfo` or
  `book.nfo` at book level plus covers whose name contains `cover` or `folder`.

## Comics

```
{Series Name ({start year})}/{volume file}
```

```
Rick and Morty (2023)/
  cover.jpg
  Volume 27.cbz
  Vol 3 - Subtitle.pdf
  Issue 8.epub
Attack on Titan (2009)/
  attackontitan_vol27.pdf
```

- Exactly **two levels**: series folder, volume files inside it. Deeper nesting and loose files in
  the library root are ignored. The `(year)` on the series folder is the series **start year**.
- The volume number is parsed from the filename, in order of preference: `vol`/`volume` + number
  (`Volume 27`, `vol 1.5`, `attackontitan_vol27`), `issue` + number or `#N` (`Issue 8`,
  `Saga #12`), or plain trailing digits (`fairytail 3`). Files without a number sort last.
- Formats: `.cbz`, `.pdf`, `.epub`. Several formats of the same volume (same base name) become one
  volume entry, and a trailing `-N` is stripped as a re-download dedupe suffix — `…part2.pdf` and
  `…part2-1.pdf` are the same volume. A `ComicInfo.xml` is read from **inside** the cbz archive,
  not from the folder.
- Series artwork: `.jpg`/`.jpeg`/`.png` whose name contains `cover`, `folder`, `poster` or
  `background` (typing per the backdrop/poster rule above).

## Podcasts

No naming rules — a `PODCAST` library has no directory on disk at all. Episodes are fetched from
the RSS feed and downloaded into the cache; see [chapter 4](04-libraries-and-media-layout.md#podcasts).

## When something is not picked up

1. Check the year format `(YYYY)` and, for shows/movies, that the year is in the right place
   (directory for shows, filename for movies).
2. Check the depth: episodes must sit in `Show (year)/Season N/`, comic volumes directly in their
   series folder — one level too deep or too shallow means invisible.
3. Check the extension against the lists above — anything else is skipped without an error.
4. After renaming, run `scanLibraries` again; renames are only picked up by a new scan.
