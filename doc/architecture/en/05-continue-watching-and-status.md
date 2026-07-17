# Continue watching and live status

Two independent mechanisms with one thing in common: neither is derived on read. The
continue-watching list is a precomputed table; live status is a fanout of in-memory registries.

## Continue watching (`recentlyWatched`)

See the [continue-watching-flow diagram](../diagrams/continue-watching-flow.md). The
`continue_watching` table (migration V20) holds one row per user per container — show / movie / book
/ podcast episode (`group_id`) — pointing at the item to resume with. `ContinueWatchingService`
(database module) owns it; the GraphQL `recentlyWatched` query is a single indexed read.

- **Incremental, same transaction.** `onWatchStatusChanged(watchStatus)` is called *inside the
  transaction* of every watch-status write (`PlayQueueService.updateWatchStatus`,
  `BookController.updateReadingProgress`, `ReadingProgressController`), so cache and truth commit
  together — no event in between. **Any new code path that writes a `WatchStatusEntity` must call
  it**, or the list goes stale until the nightly rebuild.
- **Handover on finish.** An unfinished item resumes itself; a finished one hands over to the next
  unwatched episode/chapter, found with a single indexed query
  (`EpisodeRepository.findNextUnwatchedEpisodeId`, `ChapterRepository.findNextUnfinishedChapterId`)
  — never by loading a whole show.
- **All-NULL targets survive.** When nothing is left to continue with, all target columns go NULL
  but the row deliberately stays. When the scanner later adds an episode, `recomputeForShow` (called
  from `ScannerHelperService.getOrCreateEpisode`; `recomputeForBook` for chapters) makes the new
  episode the target and the show reappears in the list. Deleting the row would make that revival
  impossible.
- **Self-healing.** `ContinueWatchingRebuildScheduler` (worker) queues
  `CONTINUE_WATCHING_REBUILD_REQUESTED` per user nightly (03:30), and once at startup while the
  table is empty (the backfill after V20). `rebuildForUser` throws the user's rows away and
  recomputes from `watch_status_entity`, which also prunes entries whose media is gone.
- **Race-safe upsert.** Writes go through a native `INSERT … ON CONFLICT DO UPDATE`
  (`ContinueWatchingRepository.upsert`) so two concurrent heartbeats of one user cannot fail on a
  unique-constraint race; `last_watched` only moves forward via `GREATEST`.
- `PreTranscodeService` reads the same table — the entries *are* the "what will they play next" set
  ([chapter 4](04-transcoding.md)) — instead of walking watch history itself.

## Live status (`core/.../status/`)

Separate from the work queues, every node publishes its state to a **fanout exchange**
(`StatusExchangeConfig`) that each node consumes on its own anonymous queue (`StatusEventListener`),
so cluster state converges everywhere and any node can answer a subscription.

| Producer | Publishes |
| --- | --- |
| `NodeActivityPublisher` | node heartbeat |
| `QueueDepthPoller` | RabbitMQ queue depths |
| `ProcessingActivityAdvice` | AOP advice reporting which handler is currently busy |
| `RecentFailuresBuffer` | recent handler failures (fed from the dead-letter path) |
| `PlaybackStatusService` | client playback heartbeats → `PlaybackSessionRegistry`, expired by `PlaybackSessionSweeper` |

`ServerStatusBroadcaster` bridges the registries to the GraphQL websocket subscriptions:
`serverActivity` and `nowPlaying` (`ServerStatusController`) and `playbackCommands(playQueueId)`
(`PlaybackCommandController` — party-mode remote control:
PLAY/PAUSE/NEXT/SEEK/SKIP_TO_ITEM/QUEUE_CHANGED).

Two invariants before touching this code:

- The activity and now-playing sinks are **replay-latest**: a new subscriber must receive current
  state immediately, and an emit from a RabbitMQ listener thread must never block.
- The command sink is deliberately **best-effort and non-replaying**: a re-subscriber replaying the
  last command would re-execute it (e.g. seek again).

Handlers here do **no database access** — RabbitMQ listener threads have no Hibernate session
([chapter 1](01-event-system.md)); everything they touch is in-memory registry state.


