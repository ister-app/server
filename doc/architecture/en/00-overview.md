# Architecture overview

Ister Server is a self-hosted media server (in the vein of Plex/Jellyfin) built with Spring Boot 4
on Java 25. It scans media libraries — movies, TV shows, music, books, comics and podcasts — fetches
metadata from external providers (TMDB, MusicBrainz, Open Library, Wikipedia/Wikidata, iTunes), and
streams HLS-transcoded media to clients over REST and GraphQL. All significant work runs
asynchronously through RabbitMQ; PostgreSQL (schema managed by Flyway) is the source of truth;
FFmpeg (driven via the Jaffree library) does the transcoding; Typesense provides optional full-text
search. Multiple nodes can form one deployment, where one node transcodes media owned by another.

The production artifact is a **GraalVM native image**, which constrains how features are toggled and
how reflection/resources are configured — see [chapter 8](08-native-image-and-testing.md).

## Modules

See the [module diagram](../diagrams/modules.md) for the picture.

| Module | Responsibility |
| --- | --- |
| `server` | Spring Boot entry point (`@SpringBootApplication`, `scanBasePackages="app.ister"`) |
| `core` | Shared infra: the `Handle<T>` interface, `MessageQueue` base names, `MessageSender`, event-data DTOs, Jaffree utilities |
| `database` | JPA entities, repositories, and the `EventType` enum |
| `api` | REST controllers, GraphQL schema and resolvers |
| `disk` | Filesystem scanning, startup tasks, file-type event handlers (media, audio, image, subtitle, NFO, epub, comic) |
| `worker` | Async metadata handlers: TMDB, MusicBrainz, Open Library, Wikipedia/Wikidata, podcast feeds |
| `search` | Optional Typesense full-text search (package `app.ister.search`) |
| `transcoder` | FFmpeg-based HLS transcoding |

**Dependency flow:** `server` → `{api, disk, worker, search, transcoder}` → `core` → `database`.
`core` declares `api project(':database')`, so entities, repositories and enums arrive
**transitively**. That is the direction, and it is easy to get backwards: `database` depends on
nothing internal, and nothing may ever make it depend on `core`. `api` additionally depends directly
on `search` and `transcoder`.

**Split-package warning:** `core/` and `database/` both contribute to package `app.ister.core.*`.
Entities, repositories and `EventType` live in `database/`; the
`Handle`/`MessageQueue`/status/config infrastructure lives in `core/`. The package name alone does
not tell you which module a class is in.

## Tech stack

| Concern | Technology |
| --- | --- |
| Framework | Spring Boot 4, Java 25, Gradle multi-module |
| Messaging | RabbitMQ (work queues + a status fanout exchange) |
| Database | PostgreSQL, Flyway migrations (`database/.../db/migration/`, forward-only) |
| Transcoding | FFmpeg via Jaffree, HLS output |
| Search | Typesense (optional, runtime-toggled) |
| Auth | OAuth2 JWT resource server (Keycloak-compatible OIDC) + short-lived stream tokens |
| Prod artifact | GraalVM native image (`nativeCompile`, `Dockerfile.native`) |
| Quality gate | SonarCloud + Jacoco (no separate linter/formatter) |

## Chapters

1. [Event system](01-event-system.md) — `Handle<T>`, `EventType` vs `MessageQueue`, queue scoping,
   retries and the dead-letter queue
2. [Scanning and analysis](02-scanning-and-analysis.md) — startup bootstrap, library scans, analyze
   flows, the BlurHash sweep
3. [Media types and metadata](03-media-types-and-metadata.md) — per-type pipelines: film/TV, music,
   books, comics, podcasts, NFO, languages
4. [Transcoding](04-transcoding.md) — lazy HLS segments, one pass per quality, concurrency,
   pre-transcode, multi-node
5. [Continue watching and live status](05-continue-watching-and-status.md) — the precomputed
   `continue_watching` table and the status fanout
6. [Search](06-search.md) — Typesense indexing, reindex with alias swap, multilingual schema
7. [API and auth](07-api-and-auth.md) — REST/GraphQL surface, subscriptions, OIDC and stream tokens
8. [Native image and testing](08-native-image-and-testing.md) — GraalVM constraints, Flyway
   discipline, test setup, CI

## Diagrams

[Modules](../diagrams/modules.md) · [Startup](../diagrams/startup.md) · [Scan
flow](../diagrams/scan-flow.md) · [Analyze flows](../diagrams/analyze-flow.md) · [Transcode
flow](../diagrams/transcode-flow.md) · [Search flow](../diagrams/search-flow.md) ·
[Continue-watching flow](../diagrams/continue-watching-flow.md) · [Full event
overview](../diagrams/event-overview.md)


