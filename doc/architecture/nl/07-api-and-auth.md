---
description: Het REST- en GraphQL-API-oppervlak van Ister met websocket-subscriptions, plus OIDC-JWT-authenticatie, stream-tokens en epub-leesendpoints.
---

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

Voor afleveringen kent het schema naast `Episode.mediaFile` een lijst `Episode.mediaFileParts` van
`MediaFilePart { mediaFile, startInMilliseconds, durationInMilliseconds }`: de tijd-slice van de
aflevering binnen elk bestand. Voor een gewoon bestand is dat `(0, bestandsduur)`; voor een
aflevering in een multi-episode-bestand (`s04e06-e07.mkv`,
[hoofdstuk 2](02-scanning-and-analysis.md)) de eigen slice — de client opent dezelfde
file-addressed HLS-stream, seekt naar `startInMilliseconds` en behandelt `start + duration` als
einde-aflevering. `MediaFile.episodes` somt elke aflevering op die een bestand bevat, dus
`episodes.length > 1` is het "gecombineerd bestand"-signaal. Progress-heartbeats blijven de
absolute bestandspositie rapporteren.

## Voorkeuren per gebruiker en attributie

Drie kleine API-oppervlakken die de hoofdstukken hierboven slechts terloops raken:

- **Ratings** — `setRating(mediaType, mediaId, rating)` slaat de 1–10-beoordeling van de aanroepende
  gebruiker voor een media-item op (`rating: null` wist die); `RatingMediaType` dekt MOVIE / SHOW /
  EPISODE / ALBUM / TRACK / BOOK / PODCAST. De waarde wordt per gebruiker teruggelezen via een
  `rating`-veld op het bijbehorende type (bijv. `Movie.rating`), null als er geen rating is.
  `RatingController`.
- **Track-afspeelstatistieken** — `Track.playCount` en `Track.lastPlayedAt` (ISO-8601) tonen de
  plays van de aanroepende gebruiker, afgeleid van track-watch-status-rijen
  ([hoofdstuk 5](05-continue-watching-and-status.md)); beide null als de track nooit is
  afgespeeld. Toplijsten per artiest staan op `Person`: `topPlayedTracks`,
  `recentlyPlayedTracks` en `topRatedTracks` (allemaal per aanroepende gebruiker, `limit`
  begrensd op 1–50, standaard 10, library-gescoped zoals elke andere resolver).
  `PersonController` / `TrackController`.
- **De muziek van een artiest** — `tracks(artistId:)` geeft elk nummer waarop de artiest
  gecrediteerd staat, als primaire of featured artiest, plus de nummers op de albums die zij zelf
  bezit; `albums(appearsOnArtistId:)` geeft de albums waarop zij gecrediteerd staat zonder ze te
  bezitten (verzamelalbums, gastoptredens). `Track.artists` toont de credits zelf (`TrackCredit`:
  persoon, `PRIMARY`/`FEATURED`, positie), terwijl `Track.artist` de primaire artiest blijft. Beide
  queries zijn library-gescoped en pagineren en sorteren als de rest van het browse-oppervlak;
  `filter` gaat voor het artiest-argument. `TrackController` / `AlbumController`.
- **Playback-instellingen** — `userSettings` / `updateUserSettings` bevatten per gebruiker
  `preferredAudioLanguages`, `preferredSubtitleLanguages`, `directPlay`, `transcode` en
  `maxVideoHeight`. Ze gelden voor elke client van die gebruiker **en sturen pre-transcoding**:
  alleen de voorkeurstalen voor audio en videovarianten tot `maxVideoHeight` worden op de
  achtergrond getranscodeerd ([hoofdstuk 4](04-transcoding.md)). Standaardwaarden vallen terug op de
  geconfigureerde talen van de server. `UserSettingsController`.
- **Attributie** — `attributions` geeft de externe providers terug die daadwerkelijk op deze server
  in gebruik zijn, voor het attributiescherm van de client: `source` (een `MetadataSource`: TMDB,
  MUSICBRAINZ, COVER_ART_ARCHIVE, WIKIMEDIA_COMMONS, WIKIPEDIA, WIKIDATA, OPEN_LIBRARY, PODCAST_FEED,
  LOCAL_FILE), een weer te geven `name`/`url`, een door de provider voorgeschreven `notice` (bijv.
  de non-endorsement-regel van TMDB) en waar relevant een content-`license` (bijv. `CC BY-SA 4.0`
  voor Wikipedia-tekst). Elke `Metadata`-rij en afbeelding draagt ook zijn eigen `source`, zodat een
  item veld voor veld geattribueerd kan worden ([hoofdstuk 3](03-media-types-and-metadata.md)).
  `AttributionController`, gebaseerd op migratie V26.

Admins, zichtbaarheid per library en het delen van playback-sessies zijn een eigen oppervlak — zie
de beheergids, [Gebruikers, delen en toegang](../../admin/nl/08-users-sharing-and-access.md), en
[hoofdstuk 5](05-continue-watching-and-status.md#sessies-delen--privacy) voor de interne werking van
het delen.

## Authenticatie

De primaire auth is **OAuth2 JWT** via Spring Security's resource server, tegen een
Keycloak-compatibele OIDC-provider (`OIDC_URL`-env var). De `roles`-claim van de JWT wordt met een
`ROLE_`-prefix op Spring-authorities gemapt (`OIDCSecurityConfig`), dus een realm-rol `admin` wordt
`ROLE_admin` en begrenst de admin-only-mutations via `@PreAuthorize("hasRole('admin')")`.

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
