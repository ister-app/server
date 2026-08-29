---
description: "Isters per-gebruiker-laag: de custom-filter-DSL, saved views en playlists, Discover-toplijsten, afspeelgeschiedenis, geregistreerde apparaten met commando's, en listen-along."
---

# Persoonlijke bibliotheek en apparaten

Alles in dit hoofdstuk is **per-gebruiker-staat bovenop de gedeelde bibliotheek**: filters,
saved views, playlists, toplijsten, afspeelgeschiedenis, geregistreerde apparaten en
listen-along. Twee regels gelden overal. Ten eerste: alles is eigenaar-gebonden en wordt
afgedwongen als deny-as-not-found — de playlist, saved view of het apparaat van iemand anders
geeft geen 403, het bestáát niet ([API & auth](07-api-and-auth.md)). Ten tweede: de
browse-queries blijven toegangsgecontroleerd — een filter of playlist kan nooit media tonen uit
een library die de aanroeper niet mag zien.

## De filter-DSL

De browse-queries (`shows`, `movies`, `episodes`, `tracks`, `albums`, `artists`) accepteren een
optionele `filter: MediaFilterInput` — een recursieve groep condities:

- Een **groep** combineert haar `conditions` en geneste `groups` met `match: ALL` (EN) of
  `ANY` (OF); groepen nesten willekeurig diep. `limit` begrenst het totale resultaat en mag
  alleen op de bovenste groep.
- Een **conditie** is `field` + `operator` + string-`value`; getallen, datums (`YYYY-MM-DD`) en
  booleans worden server-side geparset. `IS_SET`/`IS_NOT_SET` nemen geen waarde.
- Welke `FilterField`s gelden hangt af van de browse-soort (`FilterKind`): `TITLE`/`GENRE`/
  `DATE_ADDED` overal; `ARTIST_NAME` op albums en tracks; `ALBUM_NAME`, `PLAY_COUNT`,
  `LAST_PLAYED_AT` op tracks; `RELEASE_YEAR` overal behalve artiesten (die `BIRTH_YEAR`
  krijgen); `RATING` (de eigen 1-10-waardering van de aanroeper) overal behalve artiesten;
  `DURATION` op tracks, films en afleveringen; `WATCHED` op films en afleveringen. De operators
  per waardetype staan gedocumenteerd op `FilterOperator` in `schema.graphqls`.

Merk op dat `RATING`, `PLAY_COUNT`, `LAST_PLAYED_AT` en `WATCHED` filters **per definitie
per-gebruiker** maken: hetzelfde filter geeft per aanroeper andere resultaten.

## Saved views en FILTER-play-queues (V32)

Een **saved view** (`SavedView`, `SavedViewController`) is een benoemd filter over één
`FilterKind`, optioneel beperkt tot één library, met eigen sortering — het eigen browse-tabblad
van de gebruiker. CRUD via `createSavedView`/`updateSavedView`/`deleteSavedView`; browsen door
een view speelt gewoon het opgeslagen filter af door de normale browse-queries.

Een play queue kan uit een filter worden gemaakt (`PlayQueueType.FILTER`). De queue **bevriest
de filterdefinitie bij aanmaak**: hij blijft spelen met de definitie waarmee hij gemaakt is,
dus een latere bewerking van de saved view verandert een al spelende queue niet.

## Playlists (V33)

`Playlist` (`PlaylistController`) is een privé playlist per gebruiker over **precies één
library**; `libraryId` en `type` zijn na aanmaak onveranderlijk.

- **MANUAL**-playlists bevatten expliciete `PlaylistItem`s (tracks, films, afleveringen,
  podcastafleveringen of boeken, passend bij het librarytype). De `position` van een item is
  een opake float-sorteersleutel — herordenen schrijft fractionele posities in plaats van de
  rest te hernummeren.
- **SMART**-playlists bevatten hun eigen filter (`filterKind` TRACK, MOVIE of EPISODE, passend
  bij het librarytype) plus sortering, en lossen dat live op bij browsen of afspelen. Afspelen
  bevriest het filter op de queue, precies zoals een FILTER-bron.
- `coverImages` leidt maximaal vier verschillende covers af uit de eerste items (albumhoes voor
  een track, podcast-artwork voor een aflevering, anders de eigen afbeelding); clients tegelen
  ze tot een mozaïek.

## Discover: toplijsten (V30)

`LibraryDiscoverController` bedient de Discover-weergave van een library: `libraryById` plus
per-gebruiker `ranked*`-lijsten op `Library`, geordend op een `RankKind` — `RECENTLY_PLAYED`
(voor boeken/reeksen: recent gelezen), `MOST_PLAYED`, `HIGHEST_RATED` en `RECENTLY_ADDED` (een
ranking alleen voor ARTIST-play-queues; de Discover-lijsten geven er een lege pagina voor).
Elke lijst is beperkt tot de ene library die de aanroeper al via een toegangsgecontroleerde
query heeft opgelost, dus aparte in-libraries-varianten bestaan niet. Migratie
`V30__discover_indexes.sql` bevat de indexen waarop deze rankings leunen. Dezelfde `RankKind`
voedt ook ARTIST-play-queues ("speel deze artiest, meest gespeeld eerst").

## Afspeelgeschiedenis

`PlaybackHistoryController` + `PlaybackHistoryService` (database-module) laten een gebruiker de
eigen `watch_status_entity`-rijen lezen en bewerken:

- `playbackHistory(mediaType, mediaId)` — de eigen afspeelbeurten van één item, nieuwste eerst.
  Films, afleveringen, tracks en podcastafleveringen krijgen één regel per beurt; boeken en
  luisterboek-hoofdstukken houden één bijgewerkte regel; BOOK/COMIC voegt de
  hoofdstuk-luisterbeurten samen.
- `trackPlaybackHistory(scope: ALBUM|ARTIST, id, limit)` — beurten over een album of over de
  gecrediteerde tracks van een artiest (default limit 100, max 500), toegangsgefilterd.
- `markPlayed` maakt kunstmatig een afgeronde regel aan; `deleteWatchStatus` verwijdert er een.

Beide mutaties lopen via `ContinueWatchingService.onWatchStatusChanged` — de invariant uit
[Continue watching](05-continue-watching-and-status.md) waaraan élke `WatchStatusEntity`-write
zich moet houden. Het verwijderen van de regel waar een continue-watching-rij naar wijst,
herstelt die rij dus in dezelfde transactie in plaats van hem te laten bungelen.

## Apparaten, presence en apparaatcommando's (V34)

Een clientinstallatie registreert zichzelf als **apparaat**: `registerDevice(deviceId, name,
platform)`, waarbij `deviceId` een **door de client gegenereerd install-id** is — uniek per
gebruiker, niet wereldwijd. De rij is duurzaam (`device_entity`); **presence** niet: de client
pingt (`pingDevice`) elke ~20 s, presence reist mee op de status-fanout-exchange
(`DevicePresenceRegistry`, [hoofdstuk 05](05-continue-watching-and-status.md)) zodat elke node
weet welke apparaten online zijn, en verloopt na de sessietime-out van 60 s
(`PlaybackSessionSweeper`). Registreren telt als eerste ping, dus een apparaat is direct
bereikbaar.

`sendDeviceCommand` publiceert een `DeviceCommand` naar een van de **eigen, online** apparaten
van de aanroeper, afgeleverd via de `deviceCommands(deviceId)`-subscription (alleen eigen
apparaten — anders wordt de subscription geweigerd; een apparaat zonder live subscriber laat
zijn commando's stilletjes vallen). De commandotypen:

| Commando | Betekenis |
| --- | --- |
| `PLAY_MEDIA` | Start afspelen van een media-item (optioneel op een specifieke track/aflevering/hoofdstuk) op het doel |
| `TAKEOVER_QUEUE` | Draag de play queue over: het doel hervat op de meegegeven positie, de bron stopt |
| `START_FOLLOW` | Laat het doel listen-along starten op de gegeven play queue |
| `HANDOFF_QUEUE` | Pull-handoff: vraag het doel zijn actieve queue over te dragen aan `targetDeviceId` |

Aflevering is **best-effort**: `true` betekent gepubliceerd, niet uitgevoerd — hetzelfde
contract als de remote-control-commando's in [hoofdstuk 05](05-continue-watching-and-status.md).

## Listen-along (volgmodus)

Een tweede apparaat — van de eigenaar, of van elke gebruiker die de sessie op afstand mag
bedienen — kan een live afspeelsessie **volgen** (`followPlayQueue`,
`PlayQueueFollowController`). Volgers spelen de queue zelf af maar rapporteren nooit voortgang;
de `updatePlayQueue` van de eigenaar schrijft de watch status **één keer per geregistreerde
volgende gebruiker**. Registraties reizen over de status-fanout zodat elke node kan antwoorden,
en verlopen zonder heartbeat op dezelfde 60 s-time-out. `FollowResult.NOT_FOUND` dekt zowel een
ontbrekende sessie als ontbrekende bedieningsrechten (deny-as-not-found); `NO_LIBRARY_ACCESS`
wordt pas onderscheiden nadat de bedieningsrechten bewezen zijn.

Strakke synchronisatie gebruikt een **gedeeld tijdlijnanker**: `updatePlayQueue` draagt
`anchorPositionMs`/`anchorServerTimeMs` ("positie X op servertijd T") en volgers extrapoleren
daarvan in plaats van heartbeats achterna te lopen. Clients meten hun klokafwijking tegen
`GET /time` — bewust zonder authenticatie (`OIDCSecurityConfig`), geen database, alleen de
klok — in korte RTT-bursts, met de mediaan. **In een cluster werkt dit alleen als elke node
NTP-gedisciplineerd is**, anders hangt de gemeten offset af van welke node antwoordde.

De sessie-eigenaar kan de volgende apparaten opvragen (`sessionFollowers`) en er een verwijderen
(`removeFollower` → een `STOP_FOLLOW`-playbackcommando met het install-id van de volger; elke
subscriber ziet het en niet-doelen negeren het). Herhaalmodus (`SET_REPEAT`) en `STOP` lopen via
dezelfde commandosink zodat afstandsbedieningen en volgers gelijk blijven — zie de commandotabel
in [hoofdstuk 05](05-continue-watching-and-status.md).
