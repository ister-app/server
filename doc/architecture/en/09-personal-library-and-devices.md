---
description: "Ister's per-user surface: the custom filter DSL, saved views and playlists, Discover top-lists, playback history, registered devices with remote commands, and listen-along."
---

# Personal library and devices

Everything in this chapter is **per-user state on top of the shared library**: filters, saved
views, playlists, ranked top-lists, playback history, registered devices, and listen-along.
Two rules hold throughout. First, everything is owner-scoped and enforced deny-as-not-found —
someone else's playlist, saved view or device does not 403, it does not exist
([API & auth](07-api-and-auth.md)). Second, the browse queries stay access-checked: a filter or
playlist can never surface media from a library the caller cannot see.

## The filter DSL

The browse queries (`shows`, `movies`, `episodes`, `tracks`, `albums`, `artists`) take an
optional `filter: MediaFilterInput` — a recursive group of conditions:

- A **group** combines its `conditions` and nested `groups` with `match: ALL` (AND) or
  `ANY` (OR); groups nest arbitrarily. `limit` caps the total result and is only allowed on the
  top-level group.
- A **condition** is `field` + `operator` + string `value`; numbers, dates (`YYYY-MM-DD`) and
  booleans are parsed server-side. `IS_SET`/`IS_NOT_SET` take no value.
- Which `FilterField`s apply depends on the browse kind (`FilterKind`): `TITLE`/`GENRE`/
  `DATE_ADDED` everywhere; `ARTIST_NAME` on albums and tracks; `ALBUM_NAME`, `PLAY_COUNT`,
  `LAST_PLAYED_AT` on tracks; `RELEASE_YEAR` everywhere except artists (which get
  `BIRTH_YEAR`); `RATING` (the caller's own 1-10 rating) everywhere except artists; `DURATION`
  on tracks, movies and episodes; `WATCHED` on movies and episodes. Operators per value type are
  documented on `FilterOperator` in `schema.graphqls`.

Note that `RATING`, `PLAY_COUNT`, `LAST_PLAYED_AT` and `WATCHED` make filters **per-user by
construction**: the same filter yields different results for different callers.

## Saved views and FILTER play queues (V32)

A **saved view** (`SavedView`, `SavedViewController`) is a named filter over one `FilterKind`,
optionally scoped to one library, with its own sorting — the user's custom browse tab. CRUD via
`createSavedView`/`updateSavedView`/`deleteSavedView`; browsing one simply replays its stored
filter through the normal browse queries.

A play queue can be created from a filter (`PlayQueueType.FILTER`). The queue **snapshots the
filter definition at creation**: it keeps playing with the definition it was created with, so a
later edit of the saved view does not mutate a queue that is already playing.

## Playlists (V33)

`Playlist` (`PlaylistController`) is a private, per-user playlist over **exactly one library**;
`libraryId` and `type` are immutable after creation.

- **MANUAL** playlists hold explicit `PlaylistItem`s (tracks, movies, episodes, podcast
  episodes or books, matching the library type). Item `position` is an opaque float sort key —
  reordering writes fractional positions instead of renumbering the tail.
- **SMART** playlists embed their own filter (`filterKind` TRACK, MOVIE or EPISODE, matching
  the library type) plus sorting, and resolve it live when browsed or played. Playing one pins
  the filter on the queue exactly like a FILTER source.
- `coverImages` derives up to four distinct covers from the first entries (album cover for a
  track, podcast artwork for its episode, the item's own image otherwise); clients tile them
  into a mosaic.

## Discover: ranked top-lists (V30)

`LibraryDiscoverController` serves the library Discover view: `libraryById` plus per-user
`ranked*` lists on `Library`, ordered by a `RankKind` — `RECENTLY_PLAYED` (for books/series:
recently read), `MOST_PLAYED`, `HIGHEST_RATED`, and `RECENTLY_ADDED` (an ARTIST-play-queue
ranking only; the Discover lists return an empty page for it). Every list is scoped to the one
library the caller already resolved through an access-checked query, so no separate
in-libraries variants exist. Migration `V30__discover_indexes.sql` carries the indexes these
rankings lean on. The same `RankKind` also seeds ARTIST play queues ("play this artist,
most-played first").

## Playback history

`PlaybackHistoryController` + `PlaybackHistoryService` (database module) let a user read and
edit their own `watch_status_entity` rows:

- `playbackHistory(mediaType, mediaId)` — the caller's plays of one item, newest first.
  Movies, episodes, tracks and podcast episodes have one entry per play; books and audiobook
  chapters keep a single updated entry; BOOK/COMIC merges in the chapter listens.
- `trackPlaybackHistory(scope: ALBUM|ARTIST, id, limit)` — plays across an album or an
  artist's credited tracks (default limit 100, max 500), access-filtered.
- `markPlayed` synthesizes a finished entry; `deleteWatchStatus` removes one.

Both mutations go through `ContinueWatchingService.onWatchStatusChanged` — the invariant from
[Continue watching](05-continue-watching-and-status.md) that every `WatchStatusEntity` write
must uphold. Deleting the entry a continue-watching row points at therefore heals the row in
the same transaction instead of leaving it dangling.

## Devices, presence, and device commands (V34)

A client installation registers itself as a **device**: `registerDevice(deviceId, name,
platform)`, where `deviceId` is a **client-generated install id** — unique per user, not
globally. The row is durable (`device_entity`); **presence** is not: the client pings
(`pingDevice`) every ~20 s, presence rides the status fan-out exchange
(`DevicePresenceRegistry`, [chapter 05](05-continue-watching-and-status.md)) so every node
knows which devices are online, and it expires after the 60 s session timeout
(`PlaybackSessionSweeper`). Registration doubles as the first ping, so a device is targetable
immediately.

`sendDeviceCommand` publishes a `DeviceCommand` to one of the caller's **own, online** devices,
delivered through the `deviceCommands(deviceId)` subscription (own devices only — the
subscription is rejected otherwise; a device without a live subscriber silently drops its
commands). The command types:

| Command | Meaning |
| --- | --- |
| `PLAY_MEDIA` | Start playback of a media item (optionally at a specific track/episode/chapter) on the target |
| `TAKEOVER_QUEUE` | Hand the play queue off: the target resumes at the given position, the source stops |
| `START_FOLLOW` | Make the target start listen-along on the given play queue |
| `HANDOFF_QUEUE` | Pull-handoff: ask the target to hand its live queue off to `targetDeviceId` |

Delivery is **best-effort**: a `true` result means published, not executed — the same contract
as the remote-control commands in [chapter 05](05-continue-watching-and-status.md).

## Listen-along (follow mode)

A second device — the owner's, or that of any user allowed to remote-control the session — can
**follow** a live playback session (`followPlayQueue`, `PlayQueueFollowController`). Followers
play the queue themselves but never report progress; the owner's `updatePlayQueue` writes the
watch status **once per registered following user**. Registrations travel over the status
fan-out so any node can answer, and expire on the same 60 s session timeout without a
heartbeat. `FollowResult.NOT_FOUND` covers both a missing session and missing control
permission (deny-as-not-found); `NO_LIBRARY_ACCESS` is only distinguished after control rights
are proven.

Tight sync uses a **shared timeline anchor**: `updatePlayQueue` carries
`anchorPositionMs`/`anchorServerTimeMs` ("position X at server time T"), and followers
extrapolate from it instead of chasing heartbeats. Clients measure their clock offset against
`GET /time` — deliberately unauthenticated (`OIDCSecurityConfig`), no database, just the wall
clock — in short RTT bursts, taking the median. **In a cluster this only works when every node
is NTP-disciplined**, or the measured offset depends on which node answered.

The session owner can list the following devices (`sessionFollowers`) and kick one
(`removeFollower` → a `STOP_FOLLOW` playback command carrying the follower's install id; every
subscriber sees it and non-targets ignore it). Repeat mode (`SET_REPEAT`) and `STOP` relay
through the same command sink so remote controls and followers stay in step — see the command
table in [chapter 05](05-continue-watching-and-status.md).
