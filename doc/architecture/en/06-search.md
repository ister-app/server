# Search (Typesense)

Full-text search is an optional module (`search/`, package `app.ister.search`) backed by Typesense.
See the [search-flow diagram](../diagrams/search-flow.md).

## Runtime toggle, not a bean condition

The enabled flag (`app.ister.typesense.enabled`) is checked **at runtime inside the handlers** —
when disabled, index events are consumed and discarded, like the TMDB-key check. It is deliberately
*not* `@ConditionalOnProperty`: bean conditions are frozen at GraalVM native-image build time, so a
conditional bean would be baked out of the production image entirely ([chapter
8](08-native-image-and-testing.md)). The module's properties file is imported via
`spring.config.import` and therefore needs an entry in
`search/src/main/resources/META-INF/native-image/resource-config.json`.

## Index updates

Everything flows through `SEARCH_INDEX_REQUESTED` (`entityType`, `entityId`, `action`), handled by
`HandleSearchIndexRequested`, which loads the entity, maps it to a document, and upserts (or
deletes) it in the collection behind the `media` alias.

Emitters:

- **Creation** comes for free: `ServerEventService.createXFoundEvent` emits an index event for every
  searchable entity type (movie, show, episode, person, album, track) at creation time.
- **Enrichment** handlers emit after their metadata saves: `MetadataSave` (TMDB), the worker
  `HandlePersonFound`/`HandleAlbumFound` (MusicBrainz), `PersonLookupService` (TMDB cast),
  `HandleNfoFileFound`, and `HandleAudioFileFound` (audio tags, including `action=DELETE` on track
  dedup).
- **Deletes**: any code that deletes a searchable entity must call
  `serverEventService.createSearchDeleteEvent(...)`. Safety nets exist — the upsert handler deletes
  the document when the entity no longer exists, and a reindex rebuilds everything — but the rule
  keeps the index correct between reindexes.

## Full reindex

The `reindexSearch` GraphQL mutation sends `SEARCH_REINDEX_REQUESTED`. The handler creates a fresh
collection (`media_v<timestamp>`), pages through all entities and imports them, then **swaps the
alias** and drops old collections — search stays live during the rebuild.

## Multilingual schema

The collection schema and the `query_by` list are generated from `LanguageProperties.tags()`: each
configured language gets `title_<tag>`, `description_<tag>` and `genre_<tag>` fields with the
matching Typesense `locale`. `SearchDocument` is a `Map<String,Object>` rather than a fixed record
precisely so the localized keys stay dynamic.

Because the schema is fixed at collection creation, **adding a language takes two steps**: a
re-scan/analyze (to create the new `MetadataEntity` rows, see [chapter
3](03-media-types-and-metadata.md)) followed by `reindexSearch`.

## Querying

The GraphQL `search(term)` query hits Typesense across all localized fields and returns a
`SearchResult` union hydrated from PostgreSQL — Typesense only decides *which* ids match; the
authoritative data always comes from the database.


