---
description: "Ister's per-type metadata pipelines: TMDB for movies and shows, MusicBrainz for music, Open Library and Wikidata for books, plus comics, podcasts and NFO files."
---

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

A movie's identity comes from its path (`Title (year)`), so the same film under two directory
titles ("De Smurfen" next to "The Smurfs") is scanned as two rows. When TMDB matches the second
one to an id another movie of the library already carries, `MovieFoundHandle` hands it to
`MovieMergeService`: the files, play-queue and playlist entries move to the enriched row, watch
status and ratings move unless the user already has one there, and the duplicate's own metadata,
images and credits are dropped. `ScannerHelperService.getOrCreateMovie` resolves a merged-away
title through the moved files, so a rescan does not recreate it. Two copies scanned in the same
pass can still slip through when both handlers run before either sets the TMDB id; the next
`refreshMetadata` merges them.

**Matching** (`TmdbSearchService` + `TmdbResultSelector`): the year in a directory name is often one
or two off and TMDB's year filter is strict, so a wrong year returns nothing or unrelated titles. The
lookup therefore widens step by step and stops at the first hit: search with the year and take an
exact (normalized) title match; repeat that in every other configured language so a Dutch directory
name matches the Dutch TMDB title; search without the year and take an exact title released within
two years of the directory year; and only then fall back to TMDB's popularity order among the
year-filtered results, restricted to titles that share at least half of the query's words. A title
that matches nothing this way stays without metadata rather than getting a wrong one; fix the
directory name and run `refreshMetadata`.

TMDB has no "untranslated" signal: asked for a language it has no translation in, it echoes the
original title (Japanese for most anime) with an empty overview. `TmdbLanguageFallback` detects
that (localized title equals the original title while the original language is neither the
requested nor the fallback language `en`) and the show/movie handlers then fetch the English
details and use its title, and its overview and tagline where the localized ones are blank, for
that language's metadata row. The row keeps the requested language, so the player and the search
index need no special case.

The details response is mined for more than title/overview. **Per language** the metadata row also
carries the localized `genre` list (comma-separated names — this is what lights up the GENRE filter
and the `genre_<tag>` search fields for video) and the `tagline`. **Language-independent** facts are
columns on the entity itself, applied once on the first successful language: `tmdbId`, `imdbId`,
`voteAverage`/`voteCount` (null while there are zero votes), `runtime` (movies/episodes, minutes),
`status`, `homepage`, `originCountry`, `studios`, collection id/name (movies, from
`belongs_to_collection`) and `networks` (shows). After the language loop, `TmdbExtrasService` makes
a handful of extra endpoint calls per item — release dates/content ratings (certification for
`app.ister.worker.tmdb.certification-country`, default `US`, falling back to US and then any),
videos (one YouTube trailer key, preferring official trailers), keywords, and TV external ids
(imdb). Each extras call is individually fault-tolerant: a failure logs and leaves the field null
instead of dead-lettering the event (the chart e2e stubs TMDB with WireMock).

Credits come along in the same pass: movie credits, show aggregate credits, and episode credits
(cast + guest stars) become `PersonEntity` + `CreditEntity` rows, written directly to the database.
A `CreditEntity` links a person to exactly one of movie/show/episode. A `PersonEntity` is shared
between actors and music artists; TMDB cast members are deduplicated against existing persons on
**exact name + birth year**. The GraphQL `Credit` type exposes the back-references
(`movie`/`show`/`episode`, batch-resolved in `CreditController`), so a filmography is queryable via
`personById { credits { movie/show/episode } }`.

### Related shows

`Show.related(limit)` (`ShowController`, `ShowRepository.findRelatedShowIds`) turns that same
enrichment into a "more like this" list, without any extra provider call: it scores the other shows
**of the same library** on shared TMDB keywords (3.0 each, capped at five so one long keyword list
cannot dominate), shared genres (1.5 each) and shared cast members (1.0 each, capped at three). An
overlapping network, origin country or a release year within five years adds half a point each, but
only ranks shows that already share content — a common broadcast year is not a relation by itself.
Shows that share nothing are left out, so a show without TMDB enrichment yields an empty list
instead of arbitrary neighbours, and the list gets better with every `refreshMetadata`.

Keywords, networks and origin countries are the comma-space joined strings on `show_entity`;
genres live per language on the metadata rows, so the query pairs the two shows' metadata rows **on
language** (the genre names themselves are translated) and takes the best-matching language. The
candidates are by definition in the library the caller was already granted access to through
`showById`, so the field needs no access check of its own. It is deliberately not a `@BatchMapping`:
only the show detail page asks for it, one show at a time.

## Music (MusicBrainz)

Artist directories become `PersonEntity` rows (`PERSON_FOUND`), albums `AlbumEntity`
(`ALBUM_FOUND`), tracks via `AUDIO_FILE_FOUND` (ffprobe + ID3 tags + embedded cover). Album identity
comes from the **path**, never from tags; the tag-derived album title lands in `MetadataEntity`, and
a row read from a file that has since left the album is replaced on the next scan instead of winning
forever. The track's artist, however, comes from the `artist`
**tag** (falling back to the path artist): on compilation albums the per-track performer only exists
there, while the album keeps the path-derived album artist. The worker's `HandleAlbumFound` queries MusicBrainz and
downloads the release-group cover; the disk-side `HandlePersonFound`/`HandleAlbumFound` look for
`artist.nfo`/`album.nfo`. Artists get a `birthYear` (MusicBrainz life-span, or the folder name)
precisely so the TMDB actor dedup above can match them.

Every track also carries **credits** (`TrackCreditEntity`): the primary artist — the same person as
`TrackEntity.personEntity` — plus any featured guests, one row per person. That join table is what
makes an artist's compilation and guest appearances reachable from their page
([chapter 7](07-api-and-auth.md)); the album keeps a single album artist.

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
- **Series** (`BookSeriesService`, core) come from three sources with fixed precedence: epub
  series metadata (calibre / EPUB 3 belongs-to-collection) is authoritative and rewrites the link
  on every scan; a path-prefix heuristic fills series-less books when ≥2 books of the author share
  the prefix before a ` - `/`: ` separator; and **Wikidata series discovery**
  (`WikidataBookSeriesService.discoverSeries`, run from `BOOK_FOUND`) links a series-less book into
  one of the author's *existing* series via its P179 (part of series) statement — it never creates
  a series, and requires a P50 (author) label match so the same-titled film or game can never link.
  The position/original-year enrichment for books that already have a series
  (`findBookInSeries`) applies the same P50 check: the series label alone cannot reject the film,
  because a film series may carry exactly the same label as the book series ("Harry Potter").
  Label matching accepts Wikidata's `mul` (default-for-all-languages) label next to the configured
  languages: bots consolidate labels that are identical across languages into it, deleting the
  per-language ones.
  Discovery covers what the other two can't see: titles without a separator ("Harry Potter en de
  steen der wijzen") and audiobook-only books without epub metadata. When epub metadata *creates* a
  series, `BOOK_FOUND` re-fires once for the author's series-less books, so discovery converges
  within one scan regardless of scan order. The analyze backfill also re-dispatches `BOOK_FOUND`
  for every series-less book whose author has a series — a rescan skips already-known files, so
  without this a book whose discovery ran too early (or failed against Wikidata at the time) would
  stay out of its series forever.
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

## Games (`LibraryType.GAME`, migration V53)

Games are the first library type whose content is **not served as a stream**: the desktop player
downloads a game, starts a local emulator (RetroArch with the libretro core named by
`GamePlatform`, RPCS3) or the game itself / Steam, and syncs the user's saves back. The server is
the library plus the per-user save cloud.

`game_entity` identity is `(library, platform, name, pathYear)`; `form` (`GameForm`) says what the
files are: `ROM`, `PS3_FOLDER`, `PC_PORTABLE_ZIP`, `PC_LINUX_INSTALLER`, `PC_LINUX_PORTABLE`,
`STEAM`. Single-file forms attach `MediaFileEntity.gameEntity` rows (`gameEntryFile = false` for
the `.bin` companions of a `.cue`); folder forms have no per-file rows (see
[chapter 2](02-scanning-and-analysis.md#games)). `title` is a display override (`PARAM.SFO`
`TITLE`, the Steam name, later a metadata provider), `name` stays the path identity.

`HandleGameFound` (worker, `GAME_FOUND`) enriches: for PS3 it reads `PARAM.SFO` (`ParamSfoParser`:
`TITLE`, `TITLE_ID`) and copies `ICON0.PNG` into the cache store as the cover when the folder has
no artwork; for PC and Steam forms it resolves **save locations** from the community
[Ludusavi manifest](https://github.com/mtkennerly/ludusavi-manifest) — `LudusaviManifestService`
downloads the ~10 MB YAML at most once per `refresh-hours`, keeps it in the cache store under
`ludusavi/manifest.yaml` and reduces it to two in-memory indexes (Steam app id → entry, normalized
title → entry); matching is strict (app id, else exact normalized title — a wrong folder is worse
than no sync). The result lands in `manifest_save_paths` (JSON, `GameSavePaths`: path templates
with Ludusavi placeholders like `<winAppData>`, plus an `os`), `manifest_matched_by` and
`steam_cloud`; an admin's `save_paths` override always wins. `GameMetadataProvider` is the seam
for an external source (IGDB) — `NoopGameMetadataProvider` today.

**Manifest and files** (`GameResourceController`, disk): `GET /game/{id}/manifest` lists every
file (path, size, `lastModified`, entry flag) plus a `version` and `updatedAt` (the newest file
time). `version` is what an installed client compares to detect that the game changed on the
server, and it is the same value as `Game.version` in GraphQL: `GameVersion` (database module)
returns the Steam `buildId` from the `.acf` when there is one, else the first 16 hex characters of
SHA-256 over `sizeBytes|fileCount|dateUpdated` — one rule from the entity alone, because the file
list of a folder game is only walked in the disk module. The manifest ETag is
`"<gameId>-<version>"` (`If-None-Match` → 304). Game files (`/game/{id}/file/{*relative}`) are
**not** immutable — a re-upload keeps the path — so they answer with
`Cache-Control: private, no-cache`, an ETag `"<size>-<lastModifiedMillis>"` and `Last-Modified`
(304 on a matching `If-None-Match`), next to the Range support. Only the save zips below are
immutable.

**Saves** (`GameSaveService`, disk): one zip per play session, uploaded raw to
`POST /game/{id}/saves?baseVersion=N`; versions count up per (user, game), the newest
`app.ister.games.save-retention` survive, objects live in the cache store under
`saves/<userId>/<gameId>/<version>.zip` and are referenced by `game_save_entity` (so the cache
cleanup keeps them). A head newer than the client's `baseVersion` answers **409** with the head's
details — another device played in between — unless `force=true`; `…/saves/{v}/restore` copies an
older version as a new head so the history stays linear. The upload is "short tx → stream to tmp →
short tx", like the admin upload's chunks.

**Achievements** come from the client, which reads them from the local emulator/store files after
a session (RPCS3's `TROPCONF.SFM` + `TROPUSR.DAT`, Steam's `appcache/stats` KeyValues):
`POST /game/{id}/achievements` carries the full definition list plus the unlocks
(`GameAchievementService`: upsert per `(game, source, externalId)`, an unlock time never moves
back), icons arrive as PNG bodies and are stored as `ImageType.ICON` images.

**Play state** is one `WatchStatusEntity` per user per game (`playTimeMs`, `dateUpdated` = last
played), written by the `recordGamePlayed` mutation, which also keeps the continue-watching entry
(type `GAME`, the game is its own group).

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

## Artwork chosen by an admin (migration V54)

Scans and providers decide an item's artwork automatically: one `COVER` and one `BACKGROUND` per
language from TMDB (`posterPath`/`backdropPath`), the MusicBrainz release-group cover, a Wikipedia
portrait, a `folder.jpg` next to the files, or — when a movie or episode has nothing else — a frame
ffmpeg grabs from the middle of the video (`HandleMediaFileFound`). Clients pick among those rows
themselves (a local file first, then the lowest id; `ImageUtil.getImageByType` in the player,
mirrored by `PlayQueueController.artworkOf`). An admin can overrule that:

- **`ImageEntity.selected`** marks the one image to show for its item and type (at most one per
  item+type). Clients and the server-side pickers try a selected image first. The flag lives on the
  row: the choice *is* a row, there is no separate override table.
- **A forced refresh keeps it.** `AnalyzeDataHandle` deletes an item's images except the selected
  ones before re-emitting `*_FOUND`; `HandleAlbumFound`/`HandlePersonFound` treat "only a selected
  image left" as "no image yet", so the automatic cover is still refetched next to the choice.
- **Where new images come from** (`ArtworkAdminService`, disk module; GraphQL in
  `ImageAdminController`, [chapter 7](07-api-and-auth.md#admin-artwork)):
  - a stored image (`selectImage`), e.g. the generated video frame over the TMDB still;
  - a provider alternative (`imageCandidates.remote` → `selectRemoteImage`): `TmdbArtworkProvider`
    (worker, a `RemoteArtworkProvider`) lists TMDB's full poster/backdrop/still/profile sets for
    movies, shows, episodes and people, with the same `TMDB://<original url>` sourceUri the scan
    uses so a candidate the server already has is recognised and selected without a download. Only
    TMDB's own image host is downloaded: the sourceUri is admin input;
  - a frame from the item's own video at a position (`selectVideoFrame`): the same ffmpeg call as
    the automatic still, through `MediaFileInputResolver`, so it works on a node that does not hold
    the file. The position is the media file's own timeline;
  - an upload (`POST /artwork/upload`, jpeg/png/webp).

  These rows are created directly — not through `IMAGE_FOUND`, the admin is waiting — under
  `artwork/<uuid>.<ext>` in the node's cache store, with the blur-hash computed inline (the sweep
  only runs on a metadata refresh). Uploads and frames get `sourceUri` `upload://<user>/<uuid>` or
  `frame://<mediaFileId>@<ms>` and thus `MetadataSource.USER` (no attribution); only those can be
  deleted again (`deleteImage`). `clearSelectedImage` hands the slot back to the automatic rule.

## NFO files

`HandleNfoFileFound` (disk) parses XML NFO files into metadata: title, description, release date for
film/TV, biography for artists, review for albums, book metadata for books.
`PERSON_FOUND`/`ALBUM_FOUND` on the disk side proactively look for `artist.nfo`/`album.nfo` next to
the media. NFO-sourced and provider-sourced metadata are deduplicated so neither overwrites richer
data from the other.


