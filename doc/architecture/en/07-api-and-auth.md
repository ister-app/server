---
description: Ister's REST and GraphQL API surface with websocket subscriptions, plus OIDC JWT authentication, stream tokens and epub reading endpoints.
---

# API and auth

## REST surface

Controllers live under `api/.../controller/` (with a few file-serving controllers in `disk/`). The
areas:

| Area | Controllers |
| --- | --- |
| Browse | movies, shows, seasons, episodes, persons, albums, tracks, chapters, books, series, podcasts + podcast episodes, credits |
| Playback | play queue, watch status, media files, stream tokens, playback commands |
| Progress | reading progress (`ReadingProgressController`), recently watched, per-user ratings (`RatingController`) |
| Playlists & discovery | playlists (`PlaylistController`), saved views (`SavedViewController`), discover rows (`LibraryDiscoverController`) — see [chapter 9](09-personal-library-and-devices.md) |
| Devices, follow & history | devices (`DeviceController`), listen-along (`PlayQueueFollowController`), playback history (`PlaybackHistoryController`), session sharing (`PlaybackSharingController`) — see [chapter 9](09-personal-library-and-devices.md) and [chapter 5](05-continue-watching-and-status.md) |
| Management | scanner (`ScannerController`, `scanLibraries`), metadata refresh (`MetadataRefreshController`, `refreshMetadata` + per-item `refresh*`), libraries, directories, user settings, user admin (`UserAdminController`) |
| Search & misc | search (`SearchController`), current user (`MeController`), server clock (`TimeController`) |
| Server | server info, server status, `.well-known` |
| File serving (disk module) | epub resources (`EpubResourceController` area), comic pages (`ComicResourceController`: `/comic/{mediaFileId}/manifest`, `/page/{index}`, `/file`), image downloads + media-file download + transcode segment upload (`FileController`, see below) |

Errors are mapped centrally in `api/.../error/` — `RestExceptionHandler` for REST,
`GraphQlExceptionResolver` for GraphQL.

The Spring Actuator runs on a separate management port, **8081** (`management.server.port` in
`core.properties`), keeping health/metrics off the public API port. Podcast directory search is
proxied through the free iTunes Search API (`ItunesSearchService`, api module; base URL is a
property like every external endpoint).

## GraphQL

The schema lives at `api/src/main/resources/graphql/schema.graphqls`; the GraphQL IDE (GraphiQL) is
enabled **unconditionally** — `spring.graphql.graphiql.enabled=true` in `core.properties` and
`/graphiql` is `permitAll` in `OIDCSecurityConfig` — not just in dev. Besides queries and mutations
there are four websocket subscriptions ([chapter 5](05-continue-watching-and-status.md)):

- `serverActivity` — node heartbeats, queue depths, busy handlers, recent failures (replay-latest)
- `nowPlaying` — active playback sessions, filtered per viewer by the owner's sharing settings
  ([chapter 5](05-continue-watching-and-status.md#session-sharing--privacy), replay-latest)
- `playbackCommands(playQueueId)` — party-mode remote control (best-effort, non-replaying); gated by
  the owner's remote-control sharing scope
- `deviceCommands(deviceId)` — commands addressed to one of the caller's own devices; see
  [chapter 9](09-personal-library-and-devices.md)

**Websocket auth** (`GraphQlWebSocketAuthConfig`): a browser cannot set an `Authorization` header on
a websocket handshake, so the JWT travels in the `connection_init` payload
(`{"Authorization": "Bearer <jwt>"}`). The interceptor stores the resulting `SecurityContext` on the
websocket session and propagates it to every subscribe message, so `@PreAuthorize` works unchanged
on subscription controllers.

For episodes the schema carries, next to `Episode.mediaFile`, an `Episode.mediaFileParts` list of
`MediaFilePart { mediaFile, startInMilliseconds, durationInMilliseconds }`: the episode's time
slice within each file. For a normal file that is `(0, file duration)`; for an episode inside a
multi-episode file (`s04e06-e07.mkv`, [chapter 2](02-scanning-and-analysis.md)) it is the episode's
own slice — the client opens the same file-addressed HLS stream, seeks to `startInMilliseconds` and
treats `start + duration` as end-of-episode. `MediaFile.episodes` lists every episode a file
contains, so `episodes.length > 1` is the "combined file" signal. Progress heartbeats keep
reporting the absolute file position.

## Per-user preferences and attribution

Three small API surfaces that the chapters above only touch in passing:

- **Ratings** — `setRating(mediaType, mediaId, rating)` stores the calling user's 1–10 rating for a
  media item (`rating: null` clears it); `RatingMediaType` covers MOVIE / SHOW / EPISODE / ALBUM /
  TRACK / BOOK / PODCAST. The value is read back per user through a `rating` field on the
  corresponding type (e.g. `Movie.rating`), null when unrated. `RatingController`.
- **Track play statistics** — `Track.playCount` and `Track.lastPlayedAt` (ISO-8601) expose the
  calling user's plays, derived from track watch-status rows
  ([chapter 5](05-continue-watching-and-status.md)); both null when never played. Per-artist top
  lists live on `Person`: `topPlayedTracks`, `recentlyPlayedTracks` and `topRatedTracks` (all
  per calling user, `limit` clamped to 1–50, default 10, library-scoped like every other
  resolver), plus `recentlyAddedTracks` — not per user, newest in the library first, the same
  artist predicate as `tracks(artistId:)`. `Album.dateAdded` and `Track.dateAdded` (ISO-8601)
  expose when the row was created by a scan. Every `Person` list has a matching `RankKind` for
  ARTIST play queues; `RECENTLY_ADDED` is artist-only (the Discover `ranked*` lists return an
  empty page for it). `PersonController` / `TrackController`.
- **An artist's music** — `tracks(artistId:)` returns every track the artist is credited on, as
  primary or featured artist, plus the tracks on the albums they own; `albums(appearsOnArtistId:)`
  returns the albums they are credited on without owning them (compilations, guest appearances).
  `Track.artists` lists the credits themselves (`TrackCredit`: person, `PRIMARY`/`FEATURED`,
  position) while `Track.artist` stays the primary artist. Both queries are library-scoped and page
  and sort like the rest of the browse surface; `filter` takes precedence over the artist argument.
  `TrackController` / `AlbumController`.
- **Playback settings** — `userSettings` / `updateUserSettings` hold each user's
  `preferredAudioLanguages`, `preferredSubtitleLanguages`, `directPlay`, `transcode`,
  `maxVideoHeight`, `autoSkipIntro` and `hideSubtitlesMatchingAudio` (V44). They apply to every
  client of that user, and two of them **steer pre-transcoding**: only the preferred audio
  languages and video variants up to `maxVideoHeight` are transcoded in the background
  ([chapter 4](04-transcoding.md) — `PassFilter` reads nothing else, so `autoSkipIntro` and
  `hideSubtitlesMatchingAudio` are purely client-side preferences). Defaults fall back to the
  server's configured languages. `UserSettingsController`.
- **Attribution** — `attributions` returns the external providers actually in use on this server,
  for the client's attribution screen: `source` (a `MetadataSource`: TMDB, MUSICBRAINZ,
  COVER_ART_ARCHIVE, WIKIMEDIA_COMMONS, WIKIPEDIA, WIKIDATA, OPEN_LIBRARY, PODCAST_FEED, LOCAL_FILE),
  a display `name`/`url`, a provider-mandated `notice` (e.g. TMDB's non-endorsement line) and a
  content `license` where relevant (e.g. `CC BY-SA 4.0` for Wikipedia text). Each `Metadata` row and
  image also carries its own `source` so a single item can be attributed field by field
  ([chapter 3](03-media-types-and-metadata.md)). `AttributionController`, backed by migration V26.

Admins, per-library visibility, and playback-session sharing are their own surface — see the admin
guide, [Users, sharing, and access](../../admin/en/09-users-sharing-and-access.md), and
[chapter 5](05-continue-watching-and-status.md#session-sharing--privacy) for the sharing internals.

## Authentication

Primary auth is **OAuth2 JWT** via Spring Security's resource server, against a Keycloak-compatible
OIDC provider (`OIDC_URL` env var). The JWT's `roles` claim is mapped to Spring authorities with a
`ROLE_` prefix (`OIDCSecurityConfig`), so a realm role `admin` becomes `ROLE_admin` and gates the
admin-only mutations via `@PreAuthorize("hasRole('admin')")`.

**Stream tokens** cover the places a media player cannot send a bearer header. HLS playlist and
segment requests may authenticate with a short-lived `?token=` query parameter
(`StreamTokenAuthenticationFilter`); the server injects the token into the playlist URIs it
generates, so the player never handles it explicitly. `StreamTokenService` sweeps expired tokens on
a schedule. In multi-node setups, `NodeTokenManager` refreshes the inter-node tokens.

### Per-library authorization on media URLs

Authentication alone does not decide what a user may fetch: `MediaAccessEnforcementFilter` (core)
enforces per-library visibility on the id-addressed media endpoints — `/hls/{mediaFileId}`,
`/epub/{mediaFileId}`, `/comic/{mediaFileId}` and `/images/{imageId}/download`. A denied resource
answers **404**, indistinguishable from a resource that does not exist. Node-to-node traffic
(`ROLE_node`) passes through, as do resources without a library (person portraits, for example).

## Image downloads

`FileController` (disk module) also serves the artwork itself: `GET /images/{id}/download` with an
**ETag and conditional GET** (`If-None-Match` → 304). The cache policy is deliberately
`private, max-age` with revalidation rather than `immutable`: a scanned library image keeps its id
when the file behind it is replaced in place, so clients must be able to revalidate cheaply —
unlike the comic and epub resources, which are immutable. Operational note: a reverse proxy in
front of the server must pass `If-None-Match`/`ETag` through, or every image request degrades to a
full download. The same controller handles `/mediaFile/{id}/download` (multi-node source reads) and
`POST /transcode/upload/{id}/{fileName}` (segment uploads, [chapter 4](04-transcoding.md)).

## Epub reading

The client's epub reader loads books lazily through
`GET /epub/{mediaFileId}/resource/{*entryPath}` (`EpubResourceController` — the `{*entryPath}`
wildcard captures the zip-entry path including slashes), which serves individual zip entries with
Range and ETag support. It accepts the same stream tokens,
plus a **cookie fallback**: subresources (CSS, images, fonts) are loaded by the browser engine
itself, which cannot append the token — the cookie set on the first request covers those.

Reading position is a `WatchStatusEntity` carrying `readingLocation` (an epubcfi) and
`readingProgress`, synced via the `updateReadingProgress` GraphQL mutation or `POST
/reading-progress`. Both paths call `ContinueWatchingService.onWatchStatusChanged` in the same
transaction — mandatory for every watch-status write ([chapter
5](05-continue-watching-and-status.md)).


