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

- [ ] Commit current working-tree WIP as starting commit
- [ ] Untrack `core-local.properties` + add to `.gitignore` (token rotation: Gerben)

## Phase 1 — Security quick wins

- [ ] Path traversal in `disk/.../FileController.java` (~line 57 download,
      ~78 upload) and `RemoteNodeClient.java:31`: `fileName` path variable is
      unsanitized → arbitrary file read (download) and **write** (upload).
      Reuse the `requireSafeFilename` approach from `transcoder/HlsController`.
- [ ] `spring.amqp.deserialization.trust.all=true` in `core.properties:47` —
      restrict allow-list to the `app.ister.core.eventdata` package (RCE/gadget risk).
- [ ] Node-token expiry race: refresh `fixedRate` is 14h (`NodeTokenManager.java:21`)
      while token TTL is also 14h (`StreamTokenService.java:56`) → intermittent
      401s between nodes. Make refresh comfortably shorter than TTL (e.g. 12h).
- [ ] `/transcode/download/**` missing from `StreamTokenAuthenticationFilter.shouldNotFilter`
      (`:57-62`) while `FileController` exposes it — align the auth surface.
- [ ] Split node token: `createNodeToken` (`StreamTokenService.java:51-59`) grants
      download + upload in one long-lived token; separate into least-privilege tokens.
- [ ] Default credentials: `DB_PASSWORD:ister` fallback and hardcoded RabbitMQ
      `user`/`password` + non-parameterized `spring.rabbitmq.host` in
      `core.properties:38-45` — make all env-overridable, no real defaults.

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

- [ ] Extend @BatchMapping to remaining N+1 fields: `Show.seasons`, `Show.metadata`,
      `Artist.albums`, `Artist.metadata`, and especially `Episode.watchStatus`
      (`EpisodeController.java:51` — one query per episode in a listing).
- [ ] Page-size clamp: Movie/Album/Show/Artist controllers accept unbounded `size`.
      Extract one shared `buildPageable(...)` helper — also removes the 4x
      copy-pasted sort/pageable/libraryId block.
- [ ] Bound or paginate `LibraryController.findAll()` / `nodeRepository.findAll()` usages.

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
