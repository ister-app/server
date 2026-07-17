# Continue-watching flow

The `recentlyWatched` list is precomputed. Heartbeats update the `continue_watching` table
synchronously in the same transaction as the watch-status write; a nightly per-user rebuild keeps
it self-healing; and the scanner recomputes a show/book when a new episode/chapter appears.

```mermaid
flowchart TD
    HB([updatePlayQueue / updateReadingProgress]) -->|"synchronous, same transaction"| S
    SC([ScannerHelperService\ngetOrCreateEpisode/Chapter]) -->|"show was finished →\nnew episode becomes the target"| S

    Cron([ContinueWatchingRebuildScheduler\n03:30 + backfill while table empty]) -->|per user| E
    E["CONTINUE_WATCHING_REBUILD_REQUESTED\n(userId)"]
    E --> H["HandleContinueWatchingRebuildRequested\n📦 worker"]
    H -->|"discard rows + recompute\nfrom watch_status_entity"| S

    S["ContinueWatchingService"]
    S -->|"upsert ON CONFLICT\n(user, entry_type, group_id)"| T[("continue_watching\n1 row per user per\nshow/movie/book/podcast")]
    T --> Q([GraphQL recentlyWatched\n1 indexed read])
    T --> P([PreTranscodeService\n→ transcode flow])
```
