# API en auth

## REST-oppervlak

Controllers staan onder `api/.../controller/` (met een paar bestandsserverende controllers in
`disk/`). De gebieden:

| Gebied | Controllers |
| --- | --- |
| Browsen | films, shows, seizoenen, afleveringen, personen, albums, tracks, chapters, boeken, series, podcasts + podcastafleveringen, credits |
| Playback | play queue, watch status, mediabestanden, stream-tokens, playback-commands |
| Voortgang | leesvoortgang (`ReadingProgressController`), recent bekeken, ratings per user (`RatingController`) |
| Beheer | scanner (scan/analyze), libraries, directories, analyze-data, gebruikersinstellingen |
| Server | serverinfo, serverstatus, `.well-known` |
| Bestanden serveren (disk-module) | epub-resources (`EpubResourceController`-gebied), strippagina's (`ComicResourceController`: `/comic/{mediaFileId}/manifest`, `/page/{index}`, `/file`), transcode-segment-upload/download (`FileController`) |

Fouten worden centraal gemapt in `api/.../error/` — `RestExceptionHandler` voor REST,
`GraphQlExceptionResolver` voor GraphQL.

De Spring Actuator draait op een aparte management-poort, **8081** (`management.server.port` in
`core.properties`), zodat health/metrics niet op de publieke API-poort zitten. Zoeken in de
podcastdirectory wordt geproxied via de gratis iTunes Search API (`ItunesSearchService`,
api-module; de base-URL is een property, zoals elk extern endpoint).

## GraphQL

Het schema staat in `api/src/main/resources/graphql/schema.graphqls`; de GraphQL-IDE (GraphiQL) is
in dev ingeschakeld. Naast queries en mutations zijn er drie websocket-subscriptions ([hoofdstuk
5](05-continue-watching-and-status.md)):

- `serverActivity` — node-heartbeats, queuedieptes, bezige handlers, recente mislukkingen
  (replay-latest)
- `nowPlaying` — actieve playback-sessies, per kijker gefilterd op de sharing-instellingen van de
  eigenaar ([hoofdstuk 5](05-continue-watching-and-status.md#sessies-delen--privacy), replay-latest)
- `playbackCommands(playQueueId)` — party-mode-afstandsbediening (best-effort, non-replaying);
  begrensd door de afstandsbedienings-scope van de eigenaar

## Authenticatie

De primaire auth is **OAuth2 JWT** via Spring Security's resource server, tegen een
Keycloak-compatibele OIDC-provider (`OIDC_URL`-env var).

**Stream-tokens** dekken de plekken waar een mediaspeler geen bearer-header kan meesturen.
HLS-playlist- en segmentverzoeken mogen authenticeren met een kortlevende
`?token=`-queryparameter (`StreamTokenAuthenticationFilter`); de server injecteert het token in de
playlist-URI's die hij genereert, zodat de speler het nooit expliciet hoeft te hanteren.
`StreamTokenService` ruimt verlopen tokens op via een schedule. In multi-node-opstellingen ververst
`NodeTokenManager` de tokens tussen nodes.

## Epub lezen

De epub-lezer van de client laadt boeken lazy via `GET /epub/{mediaFileId}/resource/{entry}`, dat
individuele zip-entries serveert met Range- en ETag-ondersteuning. Het accepteert dezelfde
stream-tokens, plus een **cookie-fallback**: subresources (CSS, afbeeldingen, fonts) worden door de
browser-engine zelf geladen, die het token niet kan meesturen — de cookie die bij het eerste
verzoek gezet wordt, dekt die af.

De leespositie is een `WatchStatusEntity` met `readingLocation` (een epubcfi) en `readingProgress`,
gesynct via de GraphQL-mutation `updateReadingProgress` of `POST /reading-progress`. Beide paden
roepen `ContinueWatchingService.onWatchStatusChanged` aan in dezelfde transactie — verplicht voor
elke watch-status-write ([hoofdstuk 5](05-continue-watching-and-status.md)).
