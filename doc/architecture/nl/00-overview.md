# Architectuuroverzicht

Ister Server is een zelf-gehoste mediaserver (in de geest van Plex/Jellyfin), gebouwd met Spring
Boot 4 op Java 25. Hij scant medialibraries — films, tv-series, muziek, boeken, strips en podcasts —
haalt metadata op bij externe providers (TMDB, MusicBrainz, Open Library, Wikipedia/Wikidata,
iTunes) en streamt HLS-getranscodeerde media naar clients via REST en GraphQL. Al het significante
werk loopt asynchroon via RabbitMQ; PostgreSQL (schema beheerd door Flyway) is de bron van waarheid;
FFmpeg (aangestuurd via de Jaffree-bibliotheek) doet het transcoderen; Typesense levert optionele
full-text search. Meerdere nodes kunnen samen één deployment vormen, waarbij de ene node media
transcodeert die de andere bezit.

Het productie-artefact is een **GraalVM native image**, en dat legt beperkingen op aan hoe features
aan/uit gezet worden en hoe reflectie/resources geconfigureerd zijn — zie [hoofdstuk
8](08-native-image-and-testing.md).

## Modules

Zie het [modulediagram](../diagrams/modules.md) voor het plaatje.

| Module | Verantwoordelijkheid |
| --- | --- |
| `server` | Spring Boot-entrypoint (`@SpringBootApplication`, `scanBasePackages="app.ister"`) |
| `core` | Gedeelde infra: de `Handle<T>`-interface, `MessageQueue`-basisnamen, `MessageSender`, event-data-DTO's, Jaffree-utilities |
| `database` | JPA-entities, repositories en de `EventType`-enum |
| `api` | REST-controllers, GraphQL-schema en resolvers |
| `disk` | Filesystem-scanning, startup-taken, bestandstype-eventhandlers (media, audio, afbeeldingen, ondertitels, NFO, epub, strips) |
| `worker` | Asynchrone metadata-handlers: TMDB, MusicBrainz, Open Library, Wikipedia/Wikidata, podcastfeeds |
| `search` | Optionele Typesense full-text search (package `app.ister.search`) |
| `transcoder` | HLS-transcoding op basis van FFmpeg |

**Dependency-richting:** `server` → `{api, disk, worker, search, transcoder}` → `core` → `database`.
`core` declareert `api project(':database')`, dus entities, repositories en enums komen
**transitief** mee. Dat is de richting, en die haal je makkelijk door elkaar: `database` hangt van
niets interns af, en niets mag ooit maken dat het van `core` afhangt. `api` hangt daarnaast direct
af van `search` en `transcoder`.

**Split-package-waarschuwing:** `core/` en `database/` dragen allebei bij aan package
`app.ister.core.*`. Entities, repositories en `EventType` staan in `database/`; de
`Handle`/`MessageQueue`/status/config-infrastructuur staat in `core/`. Aan de packagenaam alleen
zie je dus niet in welke module een klasse zit.

## Technologiestack

| Onderdeel | Technologie |
| --- | --- |
| Framework | Spring Boot 4, Java 25, Gradle multi-module |
| Messaging | RabbitMQ (werkqueues + een status-fanout-exchange) |
| Database | PostgreSQL, Flyway-migraties (`database/.../db/migration/`, forward-only) |
| Transcoding | FFmpeg via Jaffree, HLS-output |
| Search | Typesense (optioneel, op runtime togglebaar) |
| Auth | OAuth2 JWT resource server (Keycloak-compatibele OIDC) + kortlevende stream-tokens |
| Productie-artefact | GraalVM native image (`nativeCompile`, `Dockerfile.native`) |
| Kwaliteitsbewaking | SonarCloud + Jacoco (geen aparte linter/formatter) |

## Hoofdstukken

1. [Eventsysteem](01-event-system.md) — `Handle<T>`, `EventType` versus `MessageQueue`,
   queue-scoping, retries en de dead-letter-queue
2. [Scannen en analyseren](02-scanning-and-analysis.md) — startup-bootstrap, library-scans,
   analyze-flows, de BlurHash-sweep
3. [Mediatypes en metadata](03-media-types-and-metadata.md) — pijplijnen per type: film/tv, muziek,
   boeken, strips, podcasts, NFO, talen
4. [Transcoding](04-transcoding.md) — lazy HLS-segmenten, één pass per kwaliteit, concurrency,
   pre-transcode, multi-node
5. [Continue watching en live status](05-continue-watching-and-status.md) — de voorberekende
   `continue_watching`-tabel en de status-fanout
6. [Zoeken](06-search.md) — Typesense-indexering, reindex met alias-swap, meertalig schema
7. [API en auth](07-api-and-auth.md) — REST/GraphQL-oppervlak, subscriptions, OIDC en stream-tokens
8. [Native image en testen](08-native-image-and-testing.md) — GraalVM-beperkingen,
   Flyway-discipline, testopzet, CI

## Diagrammen

[Modules](../diagrams/modules.md) · [Startup](../diagrams/startup.md) ·
[Scanflow](../diagrams/scan-flow.md) · [Analyze-flows](../diagrams/analyze-flow.md) ·
[Transcodeflow](../diagrams/transcode-flow.md) · [Zoekflow](../diagrams/search-flow.md) ·
[Continue-watching-flow](../diagrams/continue-watching-flow.md) · [Volledig
eventoverzicht](../diagrams/event-overview.md)
