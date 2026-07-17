# Changelog

## server v2.0.1

| Image | Tag |
|---|---|
| `ghcr.io/ister-app/server` | `2.0.1` |
| `ghcr.io/ister-app/migrations` | `2.0.1` |

### Fixes

- fix(podcast): stop losing the initial episode downloads ([`873750b`](https://github.com/ister-app/server/commit/873750b))

### Run

```sh
docker pull ghcr.io/ister-app/server:2.0.1
```

**Full changelog**: https://github.com/ister-app/server/compare/v2.0.0...v2.0.1

## server v2.0.0

| Image | Tag |
|---|---|
| `ghcr.io/ister-app/server` | `2.0.0` |
| `ghcr.io/ister-app/migrations` | `2.0.0` |

### Breaking changes

- refactor(reader)!: remove server-hosted epub reader web app ([`5ecd324`](https://github.com/ister-app/server/commit/5ecd324))

### Features

- feat(worker): make external metadata endpoints configurable ([`369094f`](https://github.com/ister-app/server/commit/369094f))
- feat(disk): server-side downscale for comic page images (?width=) ([`eec0d86`](https://github.com/ister-app/server/commit/eec0d86))
- feat(api): expose MediaFile.format so clients stop sniffing extensions ([`fa80490`](https://github.com/ister-app/server/commit/fa80490))
- feat(comics): COMIC library type with series-first layout, cbz/pdf/epub volumes and comic reader endpoints ([`35bbf0b`](https://github.com/ister-app/server/commit/35bbf0b))
- feat(books): reliable metadata via ISBN-first Open Library, series support and clean titles ([`9b88e32`](https://github.com/ister-app/server/commit/9b88e32))

### Fixes

- fix(comics): close the page ZipFile on every path ([`beb4ea3`](https://github.com/ister-app/server/commit/beb4ea3))
- fix(core): publish entity events after the transaction commits ([`5d16f19`](https://github.com/ister-app/server/commit/5d16f19))
- fix(search): recreate a missing collection on upsert instead of dead-lettering ([`81bab63`](https://github.com/ister-app/server/commit/81bab63))

### Other

- test(transcoder): fix the delayed-mock compile error and tune the delay ([`386b60d`](https://github.com/ister-app/server/commit/386b60d))
- test(transcoder): let mocked passes complete asynchronously in the hand-off test ([`7793bde`](https://github.com/ister-app/server/commit/7793bde))
- test(server): make the library-scan integration tests robust on slow runners ([`cd09af1`](https://github.com/ister-app/server/commit/cd09af1))
- test(transcoder): widen the pass-chain await for slow CI runners ([`12e62d1`](https://github.com/ister-app/server/commit/12e62d1))
- ci: fix the version image tag — lowercase, and set it in both jobs ([`f53379c`](https://github.com/ister-app/server/commit/f53379c))
- docs: document external endpoints, image version tags and the chart e2e ([`ebe3543`](https://github.com/ister-app/server/commit/ebe3543))

### Run

```sh
docker pull ghcr.io/ister-app/server:2.0.0
```

**Full changelog**: https://github.com/ister-app/server/compare/v1.1.0...v2.0.0

## server v1.1.0

| Image | Tag |
|---|---|
| `ghcr.io/ister-app/server` | `1.1.0` |
| `ghcr.io/ister-app/migrations` | `1.1.0` |

### Features

- feat(continue-watching): one entry per book across epub and audiobook ([`5f2bb38`](https://github.com/ister-app/server/commit/5f2bb38))
- feat: add release-year sorting and per-library sort persistence ([`db1aa58`](https://github.com/ister-app/server/commit/db1aa58))

### Fixes

- fix(books): sync release_year column from metadata so sorting matches display ([`2f12093`](https://github.com/ister-app/server/commit/2f12093))
- fix(transcoder): keep pre-transcode resume alive while passes are queued ([`7a0fe26`](https://github.com/ister-app/server/commit/7a0fe26))
- fix(continue-watching): group podcast entries by podcast, not per episode ([`86e5b11`](https://github.com/ister-app/server/commit/86e5b11))

### Run

```sh
docker pull ghcr.io/ister-app/server:1.1.0
```

**Full changelog**: https://github.com/ister-app/server/compare/v1.0.0...v1.1.0

## server v1.0.0

| Image | Tag |
|---|---|
| `ghcr.io/ister-app/server` | `1.0.0` |
| `ghcr.io/ister-app/migrations` | `1.0.0` |

### Fixes

- fix(ci): push an annotated release tag ([`60ff7d7`](https://github.com/ister-app/server/commit/60ff7d7))

### Other

- docs: correct and extend CLAUDE.md ([`34c0287`](https://github.com/ister-app/server/commit/34c0287))
- ci: release nightly from a green main ([`b486932`](https://github.com/ister-app/server/commit/b486932))

### Other changes

- Cache the continue-watching list instead of recomputing it per call ([`307d123`](https://github.com/ister-app/server/commit/307d123))
- Resume an audiobook where the listener actually left off ([`fd85382`](https://github.com/ister-app/server/commit/fd85382))
- Clear the SonarCloud issues on main ([`1a7de6d`](https://github.com/ister-app/server/commit/1a7de6d))
- Store playback settings per user and pre-transcode only what they play ([`6343cf4`](https://github.com/ister-app/server/commit/6343cf4))
- Make the new tests assert what their comments claim ([`d08d86e`](https://github.com/ister-app/server/commit/d08d86e))
- Make the person enrichment reach the people it was written for ([`50ffc82`](https://github.com/ister-app/server/commit/50ffc82))
- Let a finished pre-transcode pass pull in the next one ([`4f3cabe`](https://github.com/ister-app/server/commit/4f3cabe))
- Cover the podcast, book and status code that shipped untested ([`16377ec`](https://github.com/ister-app/server/commit/16377ec))
- Clear the SonarQube findings on the new code ([`38733a8`](https://github.com/ister-app/server/commit/38733a8))
- Give people a biography and a portrait from Wikipedia ([`94abae7`](https://github.com/ister-app/server/commit/94abae7))
- Store each user's podcast episode order server-side ([`531ac6a`](https://github.com/ister-app/server/commit/531ac6a))
- Give two tests the beans the code under test grew ([`7b98073`](https://github.com/ister-app/server/commit/7b98073))
- Resume a book in the same place when switching between audio and text ([`d129670`](https://github.com/ister-app/server/commit/d129670))
- Attach album covers to the album their sibling tracks are on ([`84f35bf`](https://github.com/ister-app/server/commit/84f35bf))
- Route transcode events for cache-directory media files ([`2768909`](https://github.com/ister-app/server/commit/2768909))
- Make watch_status_entity.episode_entity_id nullable everywhere ([`fddfdd6`](https://github.com/ister-app/server/commit/fddfdd6))
- Drop ALL unique constraints in V14, not just the first ([`fe68770`](https://github.com/ister-app/server/commit/fe68770))
- Add book and podcast library support ([`76684fb`](https://github.com/ister-app/server/commit/76684fb))
- Add daily zombie cleanup for image cache and transcode tmp dirs ([`dcc0833`](https://github.com/ister-app/server/commit/dcc0833))
- Serve pre-transcoded segments on playback instead of re-encoding ([`fb9725b`](https://github.com/ister-app/server/commit/fb9725b))
- Fix flaky HLS transcoder tests that failed CI ([`7b79ef7`](https://github.com/ister-app/server/commit/7b79ef7))
- Add remote playback commands for party mode ([`1506c01`](https://github.com/ister-app/server/commit/1506c01))
- Test the paged cast query in CreditController ([`01b1d59`](https://github.com/ister-app/server/commit/01b1d59))
- Add per-user ratings for movies, shows, episodes, albums and tracks ([`4bc284e`](https://github.com/ister-app/server/commit/4bc284e))
- Add paged cast query for a single show, movie or episode ([`aae3e61`](https://github.com/ister-app/server/commit/aae3e61))
- Resolve the SonarCloud issues reported on main ([`8f16921`](https://github.com/ister-app/server/commit/8f16921))
- Treat embedded cover art as audio, not video ([`65e4ccd`](https://github.com/ister-app/server/commit/65e4ccd))
- Add duration and artwork to the playback session heartbeat ([`3afd224`](https://github.com/ister-app/server/commit/3afd224))
- Initialize the directory-node chain before leaving the playlist transaction ([`3e3079b`](https://github.com/ister-app/server/commit/3e3079b))
- Size the Hikari pool for playback bursts and fail faster on exhaustion ([`5dc9f32`](https://github.com/ister-app/server/commit/5dc9f32))
- Publish a registry-based heartbeat when the database is unavailable ([`657f9a5`](https://github.com/ister-app/server/commit/657f9a5))
- Replay the latest status frame to websocket subscribers ([`8197370`](https://github.com/ister-app/server/commit/8197370))
- Pre-generate playlists for audio files at scan time ([`4107349`](https://github.com/ister-app/server/commit/4107349))
- Run HLS DB reads in short transactions and support audio-only playlists ([`bb9c688`](https://github.com/ister-app/server/commit/bb9c688))
- Register native reflection for the status listener's SpEL queue lookup ([`45a6949`](https://github.com/ister-app/server/commit/45a6949))
- Add live server activity and now-playing GraphQL subscriptions ([`282c678`](https://github.com/ister-app/server/commit/282c678))
- Move the nice wrapper out of the world-writable temp dir ([`a60c77c`](https://github.com/ister-app/server/commit/a60c77c))
- Pre-transcode only half-watched items and the next episode ([`627d7b1`](https://github.com/ister-app/server/commit/627d7b1))
- Prefetch the next play queue item in the client's stream settings ([`858f997`](https://github.com/ister-app/server/commit/858f997))
- Give interactive playback absolute priority over background transcodes ([`d7ad2fd`](https://github.com/ister-app/server/commit/d7ad2fd))
- Chunk the blur-hash sweep so it cannot outlive consumer_timeout ([`a97bdcb`](https://github.com/ister-app/server/commit/a97bdcb))
- Derive album identity from the path, never from tags ([`d79b3a6`](https://github.com/ister-app/server/commit/d79b3a6))
- Send a User-Agent when downloading images ([`0ae5d54`](https://github.com/ister-app/server/commit/0ae5d54))
- Enrich music artists with photo and multilingual bio ([`a267877`](https://github.com/ister-app/server/commit/a267877))
- Give music artists a birth year for TMDB-actor linking ([`58da2ad`](https://github.com/ister-app/server/commit/58da2ad))
- Match stylized album titles in MusicBrainz cover lookup ([`1a037d2`](https://github.com/ister-app/server/commit/1a037d2))
- Fix wrong TMDB matches, missing album covers, and orphan music shows ([`f20ef2c`](https://github.com/ister-app/server/commit/f20ef2c))
- Add app-wide language list driving TMDB fetch and multilingual search ([`6590018`](https://github.com/ister-app/server/commit/6590018))
- Defer blur-hash computation out of HandleImageFound (throughput) ([`183ad94`](https://github.com/ister-app/server/commit/183ad94))
- Make Episode.number non-null to fix search union validation ([`fcf9428`](https://github.com/ister-app/server/commit/fcf9428))
- Route downloaded TMDB images by cache directory name, not node name ([`89d188d`](https://github.com/ister-app/server/commit/89d188d))
- TEMP: log deserialized TMDB show poster/backdrop path (diagnostic) ([`ae5bca3`](https://github.com/ister-app/server/commit/ae5bca3))
- Null other_path_file references on metadata/stream delete (fix analyze FK violation) ([`5578d8d`](https://github.com/ister-app/server/commit/5578d8d))
- Create image row even when the blur-hash cannot be computed ([`165e8ef`](https://github.com/ister-app/server/commit/165e8ef))
- Fix blur-hash generation crashing the native image (AWT CMMException) ([`a24da30`](https://github.com/ister-app/server/commit/a24da30))
- Treat TMDB 404 for an episode as no metadata instead of dead-lettering ([`d4410c7`](https://github.com/ister-app/server/commit/d4410c7))
- Avoid lazy association navigation in all remaining event handlers ([`7701e5e`](https://github.com/ister-app/server/commit/7701e5e))
- Avoid lazy collection navigation in MEDIA_FILE_FOUND handler ([`bb90dab`](https://github.com/ister-app/server/commit/bb90dab))
- Disable Hibernate association management to fix MEDIA_FILE_FOUND failures ([`56caec4`](https://github.com/ister-app/server/commit/56caec4))
- Log the cause when dead-lettering a message ([`cc4ca71`](https://github.com/ister-app/server/commit/cc4ca71))
- Fix CI: use valid GraalVM community JDK version 25.0.2 ([`9392148`](https://github.com/ister-app/server/commit/9392148))
- Pin CI GraalVM to community 25.1.3 ([`8d853c1`](https://github.com/ister-app/server/commit/8d853c1))
- Fix HLS audio transcode hang on AAC sources with cover art ([`975a36a`](https://github.com/ister-app/server/commit/975a36a))
- Use fixed listener concurrency to fix broken CI build ([`3e0347c`](https://github.com/ister-app/server/commit/3e0347c))
- Fix redelivery storm that stalled analyze/scan processing ([`323ec7e`](https://github.com/ister-app/server/commit/323ec7e))
- Fix search failing to start in the GraalVM native image ([`197a982`](https://github.com/ister-app/server/commit/197a982))
- Rework play queue: reordering, lazy windowing and seeded shuffle ([`3ffd7b0`](https://github.com/ister-app/server/commit/3ffd7b0))
- Ignore client disconnects during async media responses ([`b8946e2`](https://github.com/ister-app/server/commit/b8946e2))
- Add optional Typesense full-text search ([`e4e2553`](https://github.com/ister-app/server/commit/e4e2553))
- Expose Credit back-references to movie/show/episode ([`deea3e5`](https://github.com/ister-app/server/commit/deea3e5))
- Add person support with TMDB cast credits ([`667bae0`](https://github.com/ister-app/server/commit/667bae0))
- Resolve SonarCloud new-code issues ([`34b52d2`](https://github.com/ister-app/server/commit/34b52d2))
- Upgrade openapi-generator to 7.23.0; drop deprecated config ([`adf0cac`](https://github.com/ister-app/server/commit/adf0cac))
- Pin GitHub Actions to commit SHAs and update to latest versions ([`5c7d86b`](https://github.com/ister-app/server/commit/5c7d86b))
- Update build dependencies and Gradle wrapper to latest versions ([`2129036`](https://github.com/ister-app/server/commit/2129036))
- Pretranscode flow ([`25b0cd9`](https://github.com/ister-app/server/commit/25b0cd9))
- Revert rabbitmq properties ([`592d794`](https://github.com/ister-app/server/commit/592d794))
- Harden the transcoder: fix slot deadlock, timeouts, non-blocking polls ([`6bf0fe9`](https://github.com/ister-app/server/commit/6bf0fe9))
- Centralize shared dependency versions in a Gradle version catalog ([`5ac832a`](https://github.com/ister-app/server/commit/5ac832a))
- Expand README with architecture, configuration and multi-node docs ([`b9f31a3`](https://github.com/ister-app/server/commit/b9f31a3))
- Count dead-lettered events in Micrometer ([`aed570d`](https://github.com/ister-app/server/commit/aed570d))
- Add Dependabot and validate the native image on pull requests ([`eac0460`](https://github.com/ister-app/server/commit/eac0460))
- Add unit tests for worker HandleAlbumFound and HandleArtistFound ([`06a8fea`](https://github.com/ister-app/server/commit/06a8fea))
- Add GraphQL schema-wiring test for ShowController ([`e2ef307`](https://github.com/ister-app/server/commit/e2ef307))
- Boot the full application in an integration test with real containers ([`828d101`](https://github.com/ister-app/server/commit/828d101))
- Add unit tests for HlsSubtitleService and MusicBrainzService ([`89eceb7`](https://github.com/ister-app/server/commit/89eceb7))
- Test repositories against real PostgreSQL; restore Flyway auto-config ([`a3fb2b9`](https://github.com/ister-app/server/commit/a3fb2b9))
- Batch watchStatus lookups, clamp page sizes, batch lazy loading ([`62f53aa`](https://github.com/ister-app/server/commit/62f53aa))
- Fail loudly in event handlers: retry with backoff, then dead-letter ([`6c9a7c5`](https://github.com/ister-app/server/commit/6c9a7c5))
- Split node tokens by capability and refresh before expiry ([`cc23c1b`](https://github.com/ister-app/server/commit/cc23c1b))
- Restrict AMQP deserialization and parameterize RabbitMQ connection ([`f28d4b1`](https://github.com/ister-app/server/commit/f28d4b1))
- Remove unused /transcode/download endpoint ([`eb6d21b`](https://github.com/ister-app/server/commit/eb6d21b))
- Prevent path traversal in transcode upload endpoint ([`d69be50`](https://github.com/ister-app/server/commit/d69be50))
- Add architecture docs and improvement plan ([`1528db6`](https://github.com/ister-app/server/commit/1528db6))
- Batch GraphQL mappings, central error mapping and HLS filename validation ([`c3f6ae4`](https://github.com/ister-app/server/commit/c3f6ae4))
- Music support added ([`48a579e`](https://github.com/ister-app/server/commit/48a579e))
- Transcode on an other node plus pretranscoding ([`8ba2806`](https://github.com/ister-app/server/commit/8ba2806))
- Fix webvtt test ([`3e67759`](https://github.com/ister-app/server/commit/3e67759))
- Improve analyze ([`8363911`](https://github.com/ister-app/server/commit/8363911))
- Fix subtitles utf8 ([`d600616`](https://github.com/ister-app/server/commit/d600616))
- Start hls transcoding through rabbitmq ([`bb080f8`](https://github.com/ister-app/server/commit/bb080f8))
- Hls transcoder v2. Analyze episode/movie and show ([`308635b`](https://github.com/ister-app/server/commit/308635b))
- Custom cluster name ([`cb52012`](https://github.com/ister-app/server/commit/cb52012))
- Added support for movies in graphql ([`673c451`](https://github.com/ister-app/server/commit/673c451))
- Add tests ([`489861a`](https://github.com/ister-app/server/commit/489861a))
- Fix sonarqube issues ([`fd2d963`](https://github.com/ister-app/server/commit/fd2d963))
- Flyway added + Extract entities, repositories and services from core into separate database module ([`0dd6cdf`](https://github.com/ister-app/server/commit/0dd6cdf))
- Sonarqube fixes ([`5018624`](https://github.com/ister-app/server/commit/5018624))
- Multi nodes support ([`52c5da8`](https://github.com/ister-app/server/commit/52c5da8))
- Only create blur hash if not exist ([`e2a5b0e`](https://github.com/ister-app/server/commit/e2a5b0e))
- Added banner with git information ([`4430f86`](https://github.com/ister-app/server/commit/4430f86))
- Fix mockito inline warning ([`3b0f61e`](https://github.com/ister-app/server/commit/3b0f61e))
- Split project in multiple gradle modules ([`fbcb1d5`](https://github.com/ister-app/server/commit/fbcb1d5))
- Dont throw error when metadata isn't found ([`8103f0a`](https://github.com/ister-app/server/commit/8103f0a))
- By analyse also check for missing metadata ([`67341a0`](https://github.com/ister-app/server/commit/67341a0))
- Update jackson 2 -> 3. Reorder dependencies ([`23ec934`](https://github.com/ister-app/server/commit/23ec934))
- Fix native-image and openfeign tmdb client ([`6d0576e`](https://github.com/ister-app/server/commit/6d0576e))
- Fix playqueue DataIntegrityViolationException ([`b647b66`](https://github.com/ister-app/server/commit/b647b66))
- Update spring-boot 4.0 ([`a97de31`](https://github.com/ister-app/server/commit/a97de31))
- Playqueue return max 21 items ([`04f907f`](https://github.com/ister-app/server/commit/04f907f))
- Playqueue keep items in seperate table instead of json ([`9269252`](https://github.com/ister-app/server/commit/9269252))
- Fix scanner with directoryEntityId ([`4491ed0`](https://github.com/ister-app/server/commit/4491ed0))
- BlurHash added + analyze library endpoint ([`0ba0e93`](https://github.com/ister-app/server/commit/0ba0e93))
- Feature optional sorting for shows controller ([`36c302f`](https://github.com/ister-app/server/commit/36c302f))
- - Updated `isUnwatchedOrOld` method in `EpisodeController.java` to check for episodes not watched or older than **300 days** (previously **30 days**). - Modified documentation in `WatchStatusRepository.java` to clarify that recent episodes are filtered based on watch status records updated in the last **150 days**. - Adjusted SQL query in `WatchStatusRepository.java` to include a condition that filters records based on their last updated date for relevant watch status updates. ([`15e4703`](https://github.com/ister-app/server/commit/15e4703))
- Refactor episodesRecentWatched also added next episodes watched older then 30 days ([`3ef1832`](https://github.com/ister-app/server/commit/3ef1832))
- Update depedencies ([`e71662a`](https://github.com/ister-app/server/commit/e71662a))
- Update org.springdoc ([`5b75f18`](https://github.com/ister-app/server/commit/5b75f18))
- Update depedencies ([`eef2af0`](https://github.com/ister-app/server/commit/eef2af0))
- showsRecentAdded return page ([`41e309b`](https://github.com/ister-app/server/commit/41e309b))
- Sort source uri desc ([`80c87c8`](https://github.com/ister-app/server/commit/80c87c8))
- Update depedencies ([`cd5c44e`](https://github.com/ister-app/server/commit/cd5c44e))
- - GetPlayQueue - PlayQueue controller now returns PlayQueue - Scan controller now through Graphql ([`2700bcf`](https://github.com/ister-app/server/commit/2700bcf))
- Update depedencies ([`b0188d4`](https://github.com/ister-app/server/commit/b0188d4))
- Mediafiledownload added ([`d08edd8`](https://github.com/ister-app/server/commit/d08edd8))
- Update spring 3.5.3 and remove native image ([`8e69f00`](https://github.com/ister-app/server/commit/8e69f00))
- Udpate dependencies ([`93d88e2`](https://github.com/ister-app/server/commit/93d88e2))
- Serverinfo controller ([`55f5769`](https://github.com/ister-app/server/commit/55f5769))
- Update dependencies ([`c53d871`](https://github.com/ister-app/server/commit/c53d871))
- Update dependencies ([`7855e4e`](https://github.com/ister-app/server/commit/7855e4e))
- Update dependencies and reflection files ([`8d29aeb`](https://github.com/ister-app/server/commit/8d29aeb))
- Support for playing movies ([`3f1d510`](https://github.com/ister-app/server/commit/3f1d510))
- Fix sorting recent ([`24cb4a5`](https://github.com/ister-app/server/commit/24cb4a5))
- Update dependencies ([`364dba8`](https://github.com/ister-app/server/commit/364dba8))
- Initial movie support (#6) ([`67dd87d`](https://github.com/ister-app/server/commit/67dd87d))
- Remove deleted files from database (#5) ([`5e9b54e`](https://github.com/ister-app/server/commit/5e9b54e))
- Fix reflection ([`5d7cdf1`](https://github.com/ister-app/server/commit/5d7cdf1))
- Rabbitmq (#4) ([`e82e6f4`](https://github.com/ister-app/server/commit/e82e6f4))
- Update spring boot ([`429a42e`](https://github.com/ister-app/server/commit/429a42e))
- Fix tests ([`6471687`](https://github.com/ister-app/server/commit/6471687))
- Update native-image reflection config ([`8294cfa`](https://github.com/ister-app/server/commit/8294cfa))
- Update dependencies ([`e9a75f5`](https://github.com/ister-app/server/commit/e9a75f5))
- Fix eventhandler bugs ([`c5b4caa`](https://github.com/ister-app/server/commit/c5b4caa))
- Native image reflection ([`19d98a1`](https://github.com/ister-app/server/commit/19d98a1))
- Add actuator ([`8bb68a9`](https://github.com/ister-app/server/commit/8bb68a9))
- Transcoding pause (#3) ([`4188ade`](https://github.com/ister-app/server/commit/4188ade))
- Fix scanner (#2) ([`9309b9b`](https://github.com/ister-app/server/commit/9309b9b))
- Float for file size ([`83f4f28`](https://github.com/ister-app/server/commit/83f4f28))
- Get duration (#1) ([`7cc7299`](https://github.com/ister-app/server/commit/7cc7299))
- Specify place of jacoco test report ([`8a94b61`](https://github.com/ister-app/server/commit/8a94b61))
- Install ffmpeg ([`3bb2045`](https://github.com/ister-app/server/commit/3bb2045))
- Start sonarqube cloud ([`026c887`](https://github.com/ister-app/server/commit/026c887))
- Graphql ([`c281184`](https://github.com/ister-app/server/commit/c281184))
- Show only recent episodes from the current user ([`6c1de30`](https://github.com/ister-app/server/commit/6c1de30))
- Add getting metadata from themoviedb.org ([`87b5b6b`](https://github.com/ister-app/server/commit/87b5b6b))
- Fix UniqueConstraint typo ([`f2f8543`](https://github.com/ister-app/server/commit/f2f8543))
- Dockerfile with fontconfig and update docker-compose file ([`17d8596`](https://github.com/ister-app/server/commit/17d8596))
- Put src in gradle subproject ([`c41aa85`](https://github.com/ister-app/server/commit/c41aa85))
- Fix sorting of seasons in show ([`e55fe1d`](https://github.com/ister-app/server/commit/e55fe1d))
- Build native image with docker ([`6b0147b`](https://github.com/ister-app/server/commit/6b0147b))
- Update gradle-publish.yml ([`ce33bf1`](https://github.com/ister-app/server/commit/ce33bf1))
- Create gradle-publish.yml ([`f33885e`](https://github.com/ister-app/server/commit/f33885e))
- Multiple disk support and database scheme with unique constraints ([`89a590b`](https://github.com/ister-app/server/commit/89a590b))
- Added keep track of watch status ([`7b12ea7`](https://github.com/ister-app/server/commit/7b12ea7))
- Initial playqueue support ([`2eb169a`](https://github.com/ister-app/server/commit/2eb169a))
- LICENSE GPLv3 ([`3213687`](https://github.com/ister-app/server/commit/3213687))
- OIDC added ([`6a863f9`](https://github.com/ister-app/server/commit/6a863f9))
- Optimize ffmpeg commands with -ss before input ([`6836740`](https://github.com/ister-app/server/commit/6836740))
- Order episodes for season ([`86ca6df`](https://github.com/ister-app/server/commit/86ca6df))
- Save duration of mediafile ([`1439044`](https://github.com/ister-app/server/commit/1439044))
- Fix native image missing font config ([`98d2e57`](https://github.com/ister-app/server/commit/98d2e57))
- Support for srt subtitle files ([`51e80f7`](https://github.com/ister-app/server/commit/51e80f7))
- Scanner package splitted in multiple sub packages ([`9002fad`](https://github.com/ister-app/server/commit/9002fad))
- Scanner refactored with PathObject ([`c319c10`](https://github.com/ister-app/server/commit/c319c10))
- Re-enable mediafile check ([`6df7c62`](https://github.com/ister-app/server/commit/6df7c62))
- Typo in ghcr ([`e73d681`](https://github.com/ister-app/server/commit/e73d681))
- Entities notnull en superbuilder ([`440c49f`](https://github.com/ister-app/server/commit/440c49f))
- Native image and docker-compose ready ([`45ebe6b`](https://github.com/ister-app/server/commit/45ebe6b))
- NFO file support ([`699d903`](https://github.com/ister-app/server/commit/699d903))
- Use prostgres db ([`da57288`](https://github.com/ister-app/server/commit/da57288))
- Analyze and scanner seperated and thumbnail generated ([`4c2daab`](https://github.com/ister-app/server/commit/4c2daab))
- Added initial work ([`e4ea3e5`](https://github.com/ister-app/server/commit/e4ea3e5))
- Git initial ([`ff17957`](https://github.com/ister-app/server/commit/ff17957))

### Run

```sh
docker pull ghcr.io/ister-app/server:1.0.0
```

