# Maintenance and troubleshooting

Ister is designed to look after itself: caches are swept, the continue-watching list self-heals,
and failed background work is preserved for inspection. This chapter covers the moving parts you
should know about, what to back up, and the usual suspects when something looks off.

## Scheduled jobs

| Job | Module | Schedule (default) | What it does |
| --- | --- | --- | --- |
| Cache cleanup | disk | daily, 04:30 | deletes cache files no database row references ("zombies"); expires podcast downloads past `podcast-retention-days` (30) unless someone is mid-episode |
| Tmp transcode cleanup | transcoder | daily, 04:30 | same sweep for the HLS transcode tmp dir |
| Pre-transcode | worker | every 15 min | warms up HLS output for what users will likely play next |
| Continue-watching rebuild | worker | nightly, 03:30 | recomputes each user's continue-watching list from scratch; prunes entries whose media is gone |
| Podcast refresh | worker | hourly | fetches every subscribed feed, queues new episode downloads |
| Token sweeps | core/transcoder | continuous / 12 h | expire stream tokens and playback sessions; refresh node tokens |

**Important:** the cache cleanup ships with
**`app.ister.server.cache-cleanup.dry-run=true`** — by default it only *logs* what it would
delete. Run a deploy or two, check the log lines look sane, then set
`CACHE_CLEANUP_DRY_RUN=false` to let it actually reclaim disk space. It never touches files
younger than `CACHE_CLEANUP_MIN_AGE` (24h), and it never touches your media.

## Backup

**PostgreSQL is the single source of truth** — it is the only thing you must back up (plus your
media files, which are yours to begin with; the server never modifies them). Everything else is
rebuildable:

| Data | Where | Recovery |
| --- | --- | --- |
| Image cache, podcast downloads | `CACHE_DIR` | re-scan / `analyzeLibrary`; podcasts re-download |
| HLS segments | `TMP_DIR` | re-transcoded on demand |
| Typesense index | Typesense volume | one `reindexSearch` mutation |
| RabbitMQ queues | broker | transient work; a lost message at worst delays metadata until the next scan |

So: `pg_dump` on a schedule, and don't bother backing up the caches.

## Monitoring

- **Actuator** on port 8081: `/actuator/health` for probes, `/actuator/prometheus` for scraping.
- **Dead-letter queue** — failed background events are retried with backoff, then land in the
  RabbitMQ queue **`app.ister.server.dead-letter`** with the exception preserved in the message
  headers. Watch its depth (RabbitMQ management UI on 15672, or Prometheus); a growing
  dead-letter queue is the earliest sign that scanning or metadata fetching is failing.
- **Live activity** — the `serverActivity` GraphQL subscription (surfaced in the client's
  activity page) shows what each node is busy with right now.

## Troubleshooting

**No metadata after a scan (bare filenames, no posters)** — almost always a missing or wrong
TMDB key (`app.ister.server.TMDB.apikey`, an *API read access token*): metadata fetching is
skipped without it. Set it, then run `analyzeLibrary` to backfill. Also check the dead-letter
queue for rate-limit or network errors.

**Playback never starts / no transcode** — the server can't run FFmpeg. Verify `FFMPEG_DIR`
points at a directory containing `ffmpeg` and `ffprobe` (the official image has them at
`/usr/bin`). For hardware acceleration failures, try `HLS_HWACCEL=none` first to isolate the
GPU setup (device mapping, `render` group) from the pipeline.

**Item missing from (or stuck in) continue watching** — the list is precomputed; if a client
wrote progress through an unusual path it can lag. The nightly rebuild (03:30) repairs it; it
also runs once at startup when the table is empty.

**Search returns nothing** — Typesense is disabled, unreachable, or was never reindexed after
enabling. See [Search](05-search-typesense.md).

**Disk filling up** — check whether cache cleanup is still in dry-run (see above), and look at
`CACHE_DIR`/`TMP_DIR` sizes versus podcast retention and pre-transcode activity.

**New files not appearing** — there is no filesystem watcher; run `scanLibrary`. If files are
found but misclassified, compare their paths against
[the expected layout](03-libraries-and-media-layout.md).

For a deeper understanding of any of these subsystems, start at the
[architecture overview](../../architecture/en/00-overview.md).
