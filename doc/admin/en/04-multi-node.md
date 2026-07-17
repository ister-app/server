# Multi-node

One Ister deployment can span several servers ("nodes"). Typical reasons: media spread over
machines in different rooms, or a beefy machine doing the transcoding for a NAS that stores the
files. All nodes share **one PostgreSQL database and one RabbitMQ broker**; clients can talk to
any node.

## The concept

Every node runs the **same application image** with the same database/RabbitMQ settings, and
differs only in:

- `app.ister.server.name` — unique per node
- `app.ister.server.url` — how clients *and the other nodes* reach it
- `app.ister.cluster.name` — identical on every node
- the `app.ister.disk.directories[n].*` entries for the disks **this** node physically has

Startup validates the multi-node configuration and logs problems, so check the log of a newly
joined node.

## How work is routed

Most background work queues are **directory-scoped**: the queue name carries the directory name
(e.g. `app.ister.server.transcode_requested.disk1`), and each node only listens on the queues
for directories it owns. So when a client asks any node for a stream, the transcode request
lands on the node that holds the source file — no shared filesystem needed. This is also why
directory names must be unique across the cluster.

When node A transcodes for a playback session served by node B, A pushes each finished HLS
segment to B via `POST /transcode/upload/{id}/{fileName}`, authenticated with short-lived
**node tokens** that the nodes issue and refresh among themselves automatically (refreshed every
12 hours). You configure nothing for this beyond correct `app.ister.server.url` values — but
those URLs must be reachable node-to-node, not just from your browser.

## Dedicated transcoder nodes

A node can also transcode for **another node's** disks without owning any media itself: give it
no directories and instead list the directory names it should serve:

```properties
app.ister.transcoder.disks[0].name=server-1-disk1-tv
app.ister.transcoder.disks[1].name=server-1-disk1-movies
```

If `app.ister.transcoder.disks` is empty, it falls back to the node's own directories (the
normal single-node behaviour). Note: the source node must still be able to serve the file to the
transcoder — remote input is fetched over a tokenized download URL.

## Worked example

`docker-compose-nodes-local.yml` in the repository runs a complete three-node cluster against
one database and broker:

- **server-1** — owns six directories (shows, movies and music over two disks), VAAPI enabled
- **server-2** — a second full node with its own disks
- **transcoder-1** — no directories, only `app.ister.transcoder.disks[n]` entries naming
  server-1's disks: it does server-1's transcoding

Points to copy from it: each node has its **own** `CACHE_DIR`, its own published port and a
`server.url` using a real LAN IP (not `localhost` — the other nodes must reach it), while
`APP_ISTER_CLUSTER_NAME` is the same everywhere.

## Operational notes

- The client's cluster page (Settings → Cluster) shows every node and its health — the fastest
  "is everything up?" check.
- Scans, metadata and cleanup run per node for the directories it owns; you trigger `scanLibrary`
  once and each node picks up its share.
- For the internals of cross-node transcoding, see the
  [architecture documentation](../../architecture/en/04-transcoding.md).

## Where to next

- [Search](05-search-typesense.md) — one Typesense serves the whole cluster
- [Maintenance](06-maintenance-and-troubleshooting.md) — per-node caches and jobs
