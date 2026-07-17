# Media types and metadata

Every media type follows the same pattern: the scanner registers entities from the path, `*_FOUND`
events trigger enrichment from an external provider, images flow through `IMAGE_FOUND`. Metadata
providers live in `worker/.../events/`; their HTTP clients are centralized in
`worker/.../http/MetadataRestClients`, and **every external base URL is a property** with the real
service as default — the chart's e2e serves them all from one WireMock pod and fails on
dead-lettered events, so a hardcoded URL breaks CI.

## Languages

`app.ister.languages` / `ISTER_LANGUAGES` (ISO-639-1 tags, default `en,nl`, exposed as
`LanguageProperties`) is the single app-wide language list. Movie/show/episode handlers fetch TMDB
details **once per configured tag**, producing one `MetadataEntity` row per language per item; the
language is stored as **ISO-639-3** even though the fetch uses ISO-639-1. The first tag is the
primary/fallback language. The same list drives the search schema ([chapter 6](06-search.md)).
Adding a language requires a re-scan plus a reindex.

## Movies, shows, episodes (TMDB)

`MOVIE_FOUND` / `SHOW_FOUND` / `EPISODE_FOUND` handlers fetch TMDB details per language, save
`MetadataEntity` rows, and download posters/backdrops (emitted as `IMAGE_FOUND` on the cache
directory).

Credits come along in the same pass: movie credits, show aggregate credits, and episode credits
(cast + guest stars) become `PersonEntity` + `CreditEntity` rows, written directly to the database.
A `CreditEntity` links a person to exactly one of movie/show/episode. A `PersonEntity` is shared
between actors and music artists; TMDB cast members are deduplicated against existing persons on
**exact name + birth year**. The GraphQL `Credit` type exposes the back-references
(`movie`/`show`/`episode`, batch-resolved in `CreditController`), so a filmography is queryable via
`personById { credits { movie/show/episode } }`.

## Music (MusicBrainz)

Artist directories become `PersonEntity` rows (`PERSON_FOUND`), albums `AlbumEntity`
(`ALBUM_FOUND`), tracks via `AUDIO_FILE_FOUND` (ffprobe + ID3 tags + embedded cover). Album identity
comes from the **path**, never from tags. The worker's `HandleAlbumFound` queries MusicBrainz and
downloads the release-group cover; the disk-side `HandlePersonFound`/`HandleAlbumFound` look for
`artist.nfo`/`album.nfo`. Artists get a `birthYear` (MusicBrainz life-span, or the folder name)
precisely so the TMDB actor dedup above can match them.

## Person bios and portraits (Wikipedia/Wikidata)

`WikipediaService` (worker) enriches persons with multilingual biographies and portraits: Wikidata
resolves the entity and its image/sitelinks, the Wikipedia summary endpoint (a URL template
property) supplies per-language extracts. The same service backs comic-series descriptions.

## Books (`LibraryType.BOOK`)

Directory grammar is author-first: `Author/Book.epub` and `Author/Book/NNN_Chapter.mp3`. All formats
of one (normalized) book name converge on a single `BookEntity` (author = `PersonEntity`); formats
are attachments — epubs link via `MediaFileEntity.bookEntity`, audiobook mp3s via `ChapterEntity`
(streamed over the same audio-only HLS path as tracks). `HandleAudioFileFound` branches on library
type to create chapters instead of tracks.

- **Media overlays** (EPUB 3 read-aloud) are flagged on `MediaFileEntity.mediaOverlays`, detected
  exclusively from the epub **contents** (SMIL entries in the OPF manifest, parsed by
  `disk/.../epub/EpubParser`) — never from the filename.
- `BOOK_FOUND` triggers **Open Library** enrichment (description, and a cover only when none exists
  yet); **Wikidata** adds series membership (series name + position); NFO data is deduplicated
  against provider data so re-scans do not double descriptions.
- Epubs are read lazily by the client through `GET /epub/{mediaFileId}/resource/{entry}` ([chapter
  7](07-api-and-auth.md)); reading position is a `WatchStatusEntity` with `readingLocation`
  (epubcfi) + `readingProgress`.

## Comics (`LibraryType.COMIC`, migration V23)

Comics are **series-first**, the opposite of the book grammar: `{root}/{Series Name (start
year)}/Volume 27.cbz` (also `Vol 3 - Subtitle.pdf`, `Issue 8.epub`, and tolerated wild patterns;
`cover.jpg` in the series directory is series artwork). The `(YYYY)` suffix is the series **start
year**, not an author year — comics have no author in the path; parsing lives in `ComicPathObject` +
`ComicFileNameParser`, anything nested deeper than the series directory is ignored.

`ComicScanner` creates the `SeriesEntity` and the volume (a `BookEntity` without author) from the
path and attaches the file as a `MediaFileEntity`; all formats of one volume (same basename)
converge on one volume row. Content reading is asynchronous: `HandleComicFileFound` (disk) reads cbz
via `CbzParser` and PDF via **PDFBox** (`PdfParser`) — page count onto the `MediaFileEntity`,
embedded `ComicInfo.xml` (cbz) becomes volume metadata and can refine the filename-derived series
position and title, and the cover (first cbz page, or PDF page 1 rendered) is extracted to the
cache. Epub volumes reuse the full `EPUB_FILE_FOUND` pipeline. `COMIC_SERIES_FOUND` (worker,
`HandleComicSeriesFound`) adds per-language series descriptions and a thumbnail from
Wikipedia/Wikidata — local artwork always wins and is never overwritten. Pages are served to the
reader by `ComicResourceController` (`/comic/{mediaFileId}/manifest`, `/page/{index}`, `/file`).

## Podcasts (`LibraryType.PODCAST`)

The first **feed-based** library type: there is no library directory. `subscribePodcast(feedUrl)` or
the hourly `PodcastRefreshScheduler` (guarded by `lastRefreshedAt` so multiple nodes do not sweep
twice) sends `PODCAST_REFRESH_REQUESTED` (global queue). The worker's `RssFeedParser` fetches the
feed with a **conditional GET** (ETag/Last-Modified), caps at 500 items, syncs channel metadata +
cover, and creates `PodcastEpisodeEntity` rows deduplicated on guid.

The newest N episodes (`app.ister.worker.podcast.auto-download-count`, default 3) get
`PODCAST_EPISODE_DOWNLOAD_REQUESTED` on the **cache-directory queue** of the refreshing node; the
disk handler downloads the enclosure (following redirects) to `{cache}/podcasts/` and emits
`AUDIO_FILE_FOUND`, after which playback is identical to tracks. Older episodes download on demand
via the `downloadPodcastEpisode` mutation. Retention: the daily cache cleanup deletes downloads
older than `podcast-retention-days` (default 30) unless someone is mid-episode — the episode row
survives and can re-download. Directory search uses the free iTunes Search API
(`ItunesSearchService`, api module).

## NFO files

`HandleNfoFileFound` (disk) parses XML NFO files into metadata: title, description, release date for
film/TV, biography for artists, review for albums, book metadata for books.
`PERSON_FOUND`/`ALBUM_FOUND` on the disk side proactively look for `artist.nfo`/`album.nfo` next to
the media. NFO-sourced and provider-sourced metadata are deduplicated so neither overwrites richer
data from the other.


