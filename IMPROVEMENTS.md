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

## Phase 2 — Event-system reliability (highest architectural impact) ✅ (mostly)

- [x] `Handle.handle()` is now void; failure = throw (checked exceptions wrapped in
      new `EventHandlingException`). All 21 handlers converted, benign skips
      preserved as plain returns (6c9a7c5).
- [x] Retry (3x, exponential backoff, core.properties) + `app.ister.server.dead-letter`
      queue via `RepublishMessageRecoverer` in `core/.../RabbitReliabilityConfig` (6c9a7c5).
      Verified end-to-end in `server/.../IsterServerIntegrationTest` (828d101).
- [ ] Recovery for poll timeouts in HlsService/HlsTranscodeService — picked up in
      Phase 4 (same files).
- [ ] Basic metrics on processed/failed events → folded into Phase 6 observability.

## Phase 3 — Integration-test foundation (Testcontainers)

Currently ~87 test classes, all Mockito unit tests; zero `@SpringBootTest` /
`@DataJpaTest` / `@GraphQlTest`. Order of value:

- [x] Testcontainers PostgreSQL test (`database/.../PostgresRepositoryIntegrationTest`):
      Flyway V1..V6 + ddl-auto=validate + native queries on Postgres 18 (a3fb2b9).
      **Real bug found & fixed**: since the Boot 4 upgrade the `spring-boot-flyway`
      module was missing, so `spring.flyway.enabled=true` silently did nothing.
      NB: run locally with `DOCKER_HOST=unix:///run/user/1000/podman/podman.sock`
      (and `systemctl --user start podman.socket`); tests skip without it, CI runs them.
- [x] Full-application Testcontainers test (`server/.../IsterServerIntegrationTest`):
      context boots against real Postgres + RabbitMQ; failing event → 3 retries →
      dead-letter queue, payload/routing-key/exception preserved (828d101).
- [x] Context-load smoke test — covered by the same server-module test.
- [x] `@GraphQlTest` exemplar for ShowController (schema wiring + @BatchMapping
      registration, e2ef307); extend to other controllers over time.
- [x] Unit tests: `HlsSubtitleServiceTest` (13) + `MusicBrainzServiceTest` (15)
      (89eceb7); worker `HandleAlbumFound`/`HandleArtistFound` tests added separately.

## Phase 4 — Transcoder hardening ✅

- [x] Starvation/deadlock fixed: file-slot acquisition moved to a virtual thread so
      pool threads never block on the semaphore (a full pool of slot-waiters would
      have starved the queued passes of slot-holding files).
- [x] `stableSegmentOrNull` is now non-blocking: per-path {size, timestamp} samples
      instead of an inline 200ms sleep, plus a known-stable memo so repeat requests
      (players, upload watcher) return instantly.
- [x] FFmpeg runs via `executeAsync` with a timeout — passes bounded by
      `max(duration × pass-timeout-multiplier, pass-timeout-min-seconds)`,
      COPY-segments by the segment timeout; hung processes are force-stopped.
- [x] `RemoteNodeClient`: 10s connect / 5min request timeouts. `watchAndUpload`:
      bounded drain window after pass completion (`upload-drain-timeout-ms`) —
      also fixes a busy-spin when uploads kept failing after the pass finished.
- [x] Master-playlist wait now re-sends TRANSCODE_REQUESTED once at half-timeout
      (recovers from lost/dead-lettered events) — the Phase 2 leftover.
- [x] Config extracted (`app.ister.transcoder.hls.*`): `max-concurrent-passes`,
      `segment-stability-ms`, `pass-timeout-multiplier`, `pass-timeout-min-seconds`,
      `cache-retention-hours`, `upload-drain-timeout-ms`. Audio bitrate defaults and
      token TTLs deliberately left as constants (no operational need yet).

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

- [x] Gradle version catalog `gradle/libs.versions.toml` for jaffree/blurhash/jimfs
      (5ac832a). Lombok/test blocks per module left as-is (Boot BOM manages them).
- [x] Dependabot for gradle + github-actions, weekly, minor/patch grouped (eac0460).
- [x] `nativeCompile` now validated on PRs via `.github/workflows/native-build.yml` (eac0460).
- [x] Observability: `ister.events.dead.lettered` counter (aed570d); Spring AMQP
      provides per-listener timers automatically via the actuator. Structured JSON
      logging deliberately NOT enabled by default — Spring Boot supports it natively,
      set env `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` per node when needed; no
      logback-spring.xml required.
- [x] README expanded: architecture, config table, multi-node, auth, testing (b9f31a3).
      Sonar exclusion for `FileController` removed; `StartupTasks` stays excluded
      until it has tests (5ac832a).
