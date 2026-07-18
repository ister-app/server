# API and auth

## REST surface

Controllers live under `api/.../controller/` (with a few file-serving controllers in `disk/`). The
areas:

| Area | Controllers |
| --- | --- |
| Browse | movies, shows, seasons, episodes, persons, albums, tracks, chapters, books, series, podcasts + podcast episodes, credits |
| Playback | play queue, watch status, media files, stream tokens, playback commands |
| Progress | reading progress (`ReadingProgressController`), recently watched, per-user ratings (`RatingController`) |
| Management | scanner (scan/analyze), libraries, directories, analyze-data, user settings |
| Server | server info, server status, `.well-known` |
| File serving (disk module) | epub resources (`EpubResourceController` area), comic pages (`ComicResourceController`: `/comic/{mediaFileId}/manifest`, `/page/{index}`, `/file`), transcode segment upload/download (`FileController`) |

Errors are mapped centrally in `api/.../error/` — `RestExceptionHandler` for REST,
`GraphQlExceptionResolver` for GraphQL.

The Spring Actuator runs on a separate management port, **8081** (`management.server.port` in
`core.properties`), keeping health/metrics off the public API port. Podcast directory search is
proxied through the free iTunes Search API (`ItunesSearchService`, api module; base URL is a
property like every external endpoint).

## GraphQL

The schema lives at `api/src/main/resources/graphql/schema.graphqls`; the GraphQL IDE (GraphiQL) is
enabled in dev. Besides queries and mutations there are three websocket subscriptions ([chapter
5](05-continue-watching-and-status.md)):

- `serverActivity` — node heartbeats, queue depths, busy handlers, recent failures (replay-latest)
- `nowPlaying` — active playback sessions, filtered per viewer by the owner's sharing settings
  ([chapter 5](05-continue-watching-and-status.md#session-sharing--privacy), replay-latest)
- `playbackCommands(playQueueId)` — party-mode remote control (best-effort, non-replaying); gated by
  the owner's remote-control sharing scope

## Per-user preferences and attribution

Three small API surfaces that the chapters above only touch in passing:

- **Ratings** — `setRating(mediaType, mediaId, rating)` stores the calling user's 1–10 rating for a
  media item (`rating: null` clears it); `RatingMediaType` covers MOVIE / SHOW / EPISODE / ALBUM /
  TRACK / BOOK / PODCAST. The value is read back per user through a `rating` field on the
  corresponding type (e.g. `Movie.rating`), null when unrated. `RatingController`.
- **Playback settings** — `userSettings` / `updateUserSettings` hold each user's
  `preferredAudioLanguages`, `preferredSubtitleLanguages`, `directPlay`, `transcode` and
  `maxVideoHeight`. They apply to every client of that user **and steer pre-transcoding**: only the
  preferred audio languages and video variants up to `maxVideoHeight` are transcoded in the
  background ([chapter 4](04-transcoding.md)). Defaults fall back to the server's configured
  languages. `UserSettingsController`.
- **Attribution** — `attributions` returns the external providers actually in use on this server,
  for the client's attribution screen: `source` (a `MetadataSource`: TMDB, MUSICBRAINZ,
  COVER_ART_ARCHIVE, WIKIMEDIA_COMMONS, WIKIPEDIA, WIKIDATA, OPEN_LIBRARY, PODCAST_FEED, LOCAL_FILE),
  a display `name`/`url`, a provider-mandated `notice` (e.g. TMDB's non-endorsement line) and a
  content `license` where relevant (e.g. `CC BY-SA 4.0` for Wikipedia text). Each `Metadata` row and
  image also carries its own `source` so a single item can be attributed field by field
  ([chapter 3](03-media-types-and-metadata.md)). `AttributionController`, backed by migration V26.

Admins, per-library visibility, and playback-session sharing are their own surface — see the admin
guide, [Users, sharing, and access](../../admin/en/08-users-sharing-and-access.md), and
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

## Epub reading

The client's epub reader loads books lazily through `GET /epub/{mediaFileId}/resource/{entry}`,
which serves individual zip entries with Range and ETag support. It accepts the same stream tokens,
plus a **cookie fallback**: subresources (CSS, images, fonts) are loaded by the browser engine
itself, which cannot append the token — the cookie set on the first request covers those.

Reading position is a `WatchStatusEntity` carrying `readingLocation` (an epubcfi) and
`readingProgress`, synced via the `updateReadingProgress` GraphQL mutation or `POST
/reading-progress`. Both paths call `ContinueWatchingService.onWatchStatusChanged` in the same
transaction — mandatory for every watch-status write ([chapter
5](05-continue-watching-and-status.md)).


