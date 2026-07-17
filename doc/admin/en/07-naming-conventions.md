# Naming conventions per library type

The scanner derives everything from paths and filenames, so getting the names right is what makes
a library "just work". [Chapter 3](03-libraries-and-media-layout.md) gives the short version; this
page is the full reference of what the parsers actually accept, per library type. Files and
directories that match no rule are **silently ignored** — a title that never shows up is almost
always a naming problem.

Rules that apply everywhere:

- A year suffix is always exactly **four digits in parentheses**: `(2019)`. `[2019]`, `2019` and
  `(19)` are not recognised.
- Extension matching is case-insensitive (`Cover.JPG` works).
- Directories whose name starts with a `.` are skipped entirely.
- Directory names should not contain a dot — a name with a dot is mistaken for a file.

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
```

- The show directory **must** end in `(year)`; a season folder directly under the library root is
  ignored.
- Season folders are `Season {N}` (case-insensitive, `Season 1` and `season 01` both work).
- Episode files carry an `sNNeNN` token (1–4 digits each, case-insensitive): `s01e12.mkv`,
  `S05E05.mp4`. The show's title and year always come from the show directory, not the filename.
- Video containers: `.mkv`, `.mp4`. Subtitles: `.srt` next to the episode, matched by filename
  prefix; a language code between the last two dots (`s01e01.en.srt`, `s01e01.nld.srt`) sets the
  subtitle language.
- NFO files: `tvshow.nfo` at show level, `sNNeNN*.nfo` at episode level.
- Artwork: `.jpg`/`.png` whose name contains `cover` (poster) or `background`/`thumb` (backdrop).

## Movies

```
{Movie Name} ({year}).mkv
```

```
Heat (1995)/
  Heat (1995).mkv
  Heat (1995).jpg
```

- The **filename** must end in `(year)` before the extension — that is what distinguishes a movie
  from a stray video file. A wrapping folder is optional; `Movie (2024).mkv` directly in the
  library root works too.
- A suffix of letters/hyphens after the year is allowed and used for artwork:
  `Heat (1995)-thumb.jpg` becomes the backdrop.
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
  `01-Title` all work; `1-01 - Title` is read as disc 1, track 1. Without a usable number the
  track number is taken from the audio tags.
- A **flat album** directly under the library root (no artist folder) is allowed; the artist then
  comes from the `album_artist` tag in the files.
- Audio formats: `mp3`, `flac`, `aac`, `opus`, `ogg`, `wav`, `m4a`, `wma`.
- Special files: `artist.nfo` and artist images (`artist.jpg`, `folder.jpg`) at artist level;
  `album.nfo` and covers (`cover.jpg`, `folder.png`) at album level. Other `.nfo` names are
  ignored.

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
- Audio formats: as music, plus `m4b`. Special files: `artist.nfo` at author level; `album.nfo`
  or `book.nfo` plus covers at book level.

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
  volume entry. A `ComicInfo.xml` is read from **inside** the cbz archive, not from the folder.
- Series artwork: `.jpg`/`.jpeg`/`.png` whose name contains `cover`, `folder`, `poster` or
  `background`.

## Podcasts

No naming rules — a `PODCAST` library has no directory on disk at all. Episodes are fetched from
the RSS feed and downloaded into the cache; see [chapter 3](03-libraries-and-media-layout.md#podcasts).

## When something is not picked up

1. Check the year format `(YYYY)` and, for shows/movies, that the year is in the right place
   (directory for shows, filename for movies).
2. Check the depth: episodes must sit in `Show (year)/Season N/`, comic volumes directly in their
   series folder — one level too deep or too shallow means invisible.
3. Check the extension against the lists above — anything else is skipped without an error.
4. After renaming, run `scanLibrary` again; renames are only picked up by a new scan.
