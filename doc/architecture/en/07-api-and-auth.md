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
- `nowPlaying` — active playback sessions (replay-latest)
- `playbackCommands(playQueueId)` — party-mode remote control (best-effort, non-replaying)

## Authentication

Primary auth is **OAuth2 JWT** via Spring Security's resource server, against a Keycloak-compatible
OIDC provider (`OIDC_URL` env var).

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


