---
description: Enable and maintain optional Typesense full-text search in Ister, from configuration and the initial reindex to adding languages and troubleshooting.
---

# Search (Typesense)

Full-text search across all libraries is optional and backed by
[Typesense](https://typesense.org/). Without it the server runs fine — but the GraphQL `search`
query then fails with an explicit error ("Search is not configured on this server"), it does
**not** return an empty list. With it, clients get fast, typo-tolerant, multilingual search over
titles, descriptions and genres, filtered by the caller's library access
([Users, sharing, and access](09-users-sharing-and-access.md)).

## Enabling

1. Run a Typesense instance (one per cluster). `docker-compose-local.yml` shows a working
   service definition; in Kubernetes the chart can deploy it for you.
2. Point the server at it:

   ```
   TYPESENSE_ENABLED=true
   TYPESENSE_HOST=typesense
   TYPESENSE_PORT=8108          # default
   TYPESENSE_PROTOCOL=http      # default
   TYPESENSE_API_KEY=<the key Typesense was started with>
   ```

3. Restart the server, then run the **`rebuildSearchIndex`** GraphQL mutation once to build the
   initial index. Until you do, existing media is not searchable — only items touched after
   enabling would trickle in. (The collection and alias themselves are created **empty** at
   startup, so an empty-but-present collection in Typesense is normal before the first reindex.)

The enabled flag is checked at **runtime**, not baked into the image: the same image serves both
modes, and search events are simply consumed and discarded while the flag is off. That means you
can flip `TYPESENSE_ENABLED` with just a restart, no rebuild.

## Staying current

After the initial reindex you never need to run it routinely. The index maintains itself:

- new items are indexed when the scanner creates them,
- metadata enrichment (TMDB and friends) updates the entry when it lands,
- deletions remove the entry.

`rebuildSearchIndex` remains the repair tool: it rebuilds into a **fresh collection and swaps an
alias**, so search stays live during the rebuild. Reach for it after enabling search on an
existing database, after restoring a database backup, or if the index ever looks out of sync.

## Adding or removing a language

Search fields are generated per configured language (`title_en`, `description_nl`, `genre_de`, …)
— title fields weigh 5 in ranking, description and genre fields 1 — and the collection schema is
**fixed at creation time**, so a language change is a small procedure:

1. Update `ISTER_LANGUAGES` (e.g. `en,nl,de`) and restart the server(s).
2. Re-fetch metadata so the new language's rows exist in PostgreSQL: run the `refreshMetadata`
   mutation (or a re-scan) — the index can only surface metadata that exists in the database.
3. Run `rebuildSearchIndex` once. It creates a fresh collection with the new schema and swaps the
   alias.

Removing a language is the same minus step 2: reindexing simply drops its fields; nothing in
PostgreSQL is deleted.

## Troubleshooting

- **Search returns an error** ("Search is not configured on this server") — `TYPESENSE_ENABLED`
  is still `false` on the node that answered the query.
- **Search is enabled but returns an empty list** — `rebuildSearchIndex` was never run after
  enabling (the startup-created collection is empty), or the API key/host is wrong. The server
  log shows connection errors on startup and on each indexing attempt.
- **Tracing a reindex in RabbitMQ** — the mutation is called `rebuildSearchIndex`, but the queue
  it feeds is named `app.ister.server.SearchReindexRequested`; don't look for a "rebuild" queue.
- **New language not searchable** — you skipped step 2 or 3 above.
- **Index survives server restarts** but lives only in Typesense's data dir; if you lose that
  volume, one `rebuildSearchIndex` rebuilds everything from PostgreSQL. It is disposable — see
  [Maintenance](07-maintenance-and-troubleshooting.md#backup).

How indexing works internally is described in the
[architecture documentation](../../architecture/en/06-search.md).
