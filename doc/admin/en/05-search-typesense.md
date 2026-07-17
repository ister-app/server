# Search (Typesense)

Full-text search across all libraries is optional and backed by
[Typesense](https://typesense.org/). Without it the server runs fine — the GraphQL `search`
query just returns nothing. With it, clients get fast, typo-tolerant, multilingual search over
titles, descriptions and genres.

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

3. Restart the server, then run the **`reindexSearch`** GraphQL mutation once to build the
   initial index. Until you do, existing media is not searchable — only items touched after
   enabling would trickle in.

The enabled flag is checked at **runtime**, not baked into the image: the same image serves both
modes, and search events are simply consumed and discarded while the flag is off. That means you
can flip `TYPESENSE_ENABLED` with just a restart, no rebuild.

## Staying current

After the initial reindex you never need to run it routinely. The index maintains itself:

- new items are indexed when the scanner creates them,
- metadata enrichment (TMDB and friends) updates the entry when it lands,
- deletions remove the entry.

`reindexSearch` remains the repair tool: it rebuilds into a **fresh collection and swaps an
alias**, so search stays live during the rebuild. Reach for it after enabling search on an
existing database, after restoring a database backup, or if the index ever looks out of sync.

## Adding or removing a language

Search fields are generated per configured language (`title_en`, `description_nl`, …) and the
collection schema is **fixed at creation time**, so a language change is a small procedure:

1. Update `ISTER_LANGUAGES` (e.g. `en,nl,de`) and restart the server(s).
2. Re-fetch metadata so the new language's rows exist in PostgreSQL: run the `analyzeLibrary`
   mutation (or a re-scan) — the index can only surface metadata that exists in the database.
3. Run `reindexSearch` once. It creates a fresh collection with the new schema and swaps the
   alias.

Removing a language is the same minus step 2: reindexing simply drops its fields; nothing in
PostgreSQL is deleted.

## Troubleshooting

- **Search returns nothing at all** — either `TYPESENSE_ENABLED` is still `false`, the API key
  is wrong, or `reindexSearch` was never run after enabling. The server log shows connection
  errors on startup and on each indexing attempt.
- **New language not searchable** — you skipped step 2 or 3 above.
- **Index survives server restarts** but lives only in Typesense's data dir; if you lose that
  volume, one `reindexSearch` rebuilds everything from PostgreSQL. It is disposable — see
  [Maintenance](06-maintenance-and-troubleshooting.md#backup).

How indexing works internally is described in the
[architecture documentation](../../architecture/en/06-search.md).
