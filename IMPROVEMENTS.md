# Improvement Plan

Prioritized improvement plan, produced 2026-07-01 from a full codebase audit.
Work through the phases in order; check off items as they land. Keep this file
updated — it is the canonical progress tracker for this effort.

## Working agreements (agreed with Gerben, 2026-07-01)

- **Git:** commit per finished item directly on `main`, locally. Do **not** push
  unless explicitly asked. (This is an explicit, standing authorization for this
  plan — it overrides the usual "never commit unasked" rule for this work only.)
- **First action:** commit the current working-tree WIP (@BatchMapping N+1
  fixes, MessageSender dedup, HlsController filename validation) as a separate
  starting commit — it is finished and tested.
- **TMDB token:** remove `core/src/main/resources/core-local.properties` from
  git tracking (keep the file on disk) and gitignore it. Gerben rotates the
  token himself at TMDB.
- Verify with `./gradlew build` (or the affected module's tests) before each commit.
- Don't flip OSIV or blanket-lazy associations — open-in-view is load-bearing.

## Phase 0 — Baseline

- [x] Commit current working-tree WIP as starting commit (c3f6ae4, docs in 1528db6)
- [x] ~~Untrack `core-local.properties`~~ — **false positive**: the file was never
      tracked; the existing `**/*-local.*` gitignore pattern covers it and
      `git log --all` shows no history. No token rotation needed.

## Phase 1 — Security quick wins ✅

- [x] Path traversal: extracted shared `core/.../utils/SafeFilename` (with test),
      applied to `FileController.uploadTranscode` and refactored `HlsController`
      to use it (d69be50).
- [x] `spring.amqp.deserialization.trust.all=true` removed; Jackson type mapper
      now allow-lists `app.ister.core.eventdata` in worker `QueueConfig` (f28d4b1).
- [x] Node-token expiry race: refresh now 12h vs 14h TTL → 2h margin (cc23c1b).
- [x] `/transcode/download` endpoint **removed** — nothing called it (segment
      transfer is push-based via `/transcode/upload`) and node tokens never
      authorized it (eb6d21b).
- [x] Node token split into separate download/upload tokens, least privilege (cc23c1b).
- [x] RabbitMQ host/port/user/password now env-parameterized (f28d4b1). DB creds
      were already env-based; kept the localhost dev defaults since
      docker-compose-local.yml depends on them.

## Phase 2 — Event-system reliability (highest architectural impact)

- [ ] `Handle.handle()` returns `Boolean` but every `@RabbitListener` discards it —
      failed events are acked and silently dropped (`core/.../Handle.java:9`).
      Change contract: throw on failure instead of returning `false`.
- [ ] Add retry policy (e.g. 3x with backoff) + dead-letter queues for all queues
      (`worker/config/QueueConfig.java`, `transcoder/config/TranscoderQueueConfig.java`,
      disk queue configs). Currently no DLQ/retry anywhere — poison messages have no safety net.
- [ ] Recovery for poll timeouts: `waitForMasterPlaylist` (`HlsService.java:647`)
      and `waitForSegment` (`HlsTranscodeService.java:391`) just throw IOException;
      consider re-issuing the transcode event once before giving up.
- [ ] Basic metrics on processed/failed events (ties into Phase 6 observability).

## Phase 3 — Integration-test foundation (Testcontainers)

Currently ~87 test classes, all Mockito unit tests; zero `@SpringBootTest` /
`@DataJpaTest` / `@GraphQlTest`. Order of value:

- [ ] Testcontainers PostgreSQL test: apply Flyway migrations (V1..V6) and
      exercise the native queries in `WatchStatusRepository` and
      `MediaFileStreamRepository` (never run against real Postgres today).
- [ ] Testcontainers RabbitMQ test: one full event flow end-to-end
      (e.g. MEDIA_FILE_FOUND → MOVIE_FOUND), including Phase 2 retry/DLQ behavior.
- [ ] Context-load smoke test in the `server` module.
- [ ] `@GraphQlTest` coverage for the GraphQL controllers.
- [ ] Unit tests for the largest untested classes:
      `transcoder/.../HlsSubtitleService.java` (225 lines),
      `worker/.../musicbrainz/MusicBrainzService.java` (208 lines),
      `HandleAlbumFound`, `HandleArtistFound`.

## Phase 4 — Transcoder hardening

- [ ] Starvation risk: `concurrentFileSlots` semaphore (2 permits) is acquired
      *inside* the fixed 4-thread pool (`HlsTranscodeService.java:87,189`) —
      acquire the slot before submitting, or derive pool size from `max-concurrent-files`.
- [ ] `Thread.sleep(200)` on HTTP request threads in `stableSegmentOrNull`
      (`HlsTranscodeService.java:437-452`) — every segment request stalls a Tomcat thread.
- [ ] No ffmpeg timeout / orphan guard: hung ffmpeg holds thread + slot; the 15-min
      janitor (`:471`) releases the slot but never kills the process.
- [ ] `RemoteNodeClient` uses `HttpClient.newHttpClient()` with no connect/request
      timeout (`:22,33`); `watchAndUpload` (`HlsService.java:587`) can hang forever
      on an unresponsive peer.
- [ ] Extract magic numbers to config: pool size (4), poll sleep (200ms),
      cache retention (2h), synthetic keyframe interval (10.0), audio 192k/-ac 2,
      token TTLs 14h/24h — follow the existing `max-concurrent-files` pattern.

## Phase 5 — API quality

- [x] `Episode.watchStatus` and `Movie.watchStatus` converted to @BatchMapping with
      `...In` repository variants (the only true per-entity repo queries). The other
      N+1 fields (`Show.seasons`/`metadata`, `Artist.albums`/`metadata`, etc.) are
      lazy collections — solved globally with
      `hibernate.default_batch_fetch_size=50` instead of per-field batch mappings.
- [x] Page-size clamp (max 200) via shared `Paging.pageable(...)` helper, replacing
      the 4x copy-pasted sort/pageable block in Movie/Show/Artist/Album controllers.
- [ ] Bound or paginate `LibraryController.findAll()` / `nodeRepository.findAll()`
      usages. **Skipped deliberately**: libraries/nodes are config-defined and
      number a handful; pagination would complicate the API for no real risk.

## Phase 6 — Maintenance & operations

- [ ] Gradle version catalog (`gradle/libs.versions.toml`): jaffree pinned 4x,
      jimfs/blurhash/Lombok blocks duplicated per module; junit-platform-launcher
      declared both globally and per module.
- [ ] Add Dependabot or Renovate (nothing tracks CVEs; stack is bleeding-edge
      Spring Boot 4 / JDK 25).
- [ ] Validate `nativeCompile` on PRs — currently only runs post-merge
      (`.github/workflows/docker-publish.yml`).
- [ ] Observability: custom metrics (transcode/scan pipelines), health indicators,
      structured logging config (`logback-spring.xml`) for multi-node.
- [ ] Expand `README.md` (21 lines today): architecture, multi-node, OIDC,
      GraphQL, required env vars. Reconsider Sonar coverage exclusions for
      `FileController`/`StartupTasks` (real runtime code).
