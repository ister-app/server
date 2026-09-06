---
description: "Run Ister as a multi-node self-hosted media server cluster: shared database and broker, directory-scoped work routing and helper nodes for transcoding, intro detection and subtitle OCR."
---

# Multi-node

One Ister deployment can span several servers ("nodes"). Typical reasons: media spread over
machines in different rooms, or a beefy machine doing the transcoding for a NAS that stores the
files. All nodes share **one PostgreSQL database and one RabbitMQ broker**; clients can talk to
any node.

## The concept

Every node runs the **same application image** with the same database/RabbitMQ settings, and
differs only in:

- `app.ister.server.name` — unique per node (this name is also used to build the node's
  cache-directory queue, see below)
- `app.ister.server.url` — how clients *and the other nodes* reach it
- `app.ister.cluster.name` — identical on every node
- the `app.ister.disk.directories[n].*` entries for the disks **this** node physically has

Startup validates the multi-node configuration, and a conflict is fatal: a directory name that is
already claimed by another node throws
`IllegalStateException: Directory <name> name is already used by an other node` and the node
**refuses to start**. So when a newly joined node dies immediately, check its log for this message
first.

## How work is routed

Most background work queues are **directory-scoped**: the queue name carries the directory name
(e.g. `app.ister.server.TranscodeRequested.disk1`), and each node only listens on the queues
for directories it owns. So when a client asks any node for a stream, the transcode request
lands on the node that holds the source file — no shared filesystem needed. This is also why
directory names must be unique across the cluster.

Besides its library directories, every node listens on one **cache-directory queue** named
`<serverName>-cache-directory` (created at startup; podcast downloads, for example, route through
it so the audio lands on the right node's cache). The routing key is the server *name*, so
`app.ister.server.name` must be cluster-unique too, not just for display.

When node A transcodes for a playback session served by node B, A pushes each finished HLS
segment to B via `POST /transcode/upload/{id}/{fileName}`, authenticated with short-lived
**node tokens** that the nodes issue and refresh among themselves automatically (refreshed every
12 hours). You configure nothing for this beyond correct `app.ister.server.url` values — but
those URLs must be reachable node-to-node, not just from your browser.

## Helper nodes

Every node stays responsible for its own directories. On top of that, a powerful node can
**help** other nodes with the CPU-heavy job families — it does not need to own any media itself:

| Job | What it covers |
| --- | --- |
| `TRANSCODE` | HLS (pre)transcoding |
| `DETECT_SEGMENTS` | intro/outro detection (audio fingerprinting) |
| `SUBTITLES` | extraction of embedded subtitles, including OCR of DVD/Blu-ray bitmap subtitles |

List the directory names the helper should serve, and optionally which jobs:

```properties
app.ister.helper.disks[0].name=server-1-disk1-tv
app.ister.helper.disks[1].name=server-1-disk1-movies
app.ister.helper.disks[1].jobs=DETECT_SEGMENTS,SUBTITLES
# default job set for disks without their own "jobs" (default: all three)
app.ister.helper.jobs=TRANSCODE,DETECT_SEGMENTS,SUBTITLES
```

The helper then consumes the **same queues** as the owner for those directories, and RabbitMQ
shares the work between them one message at a time — the faster node simply takes more. The
helper reads the source file over HTTP from the owner (a tokenized download with byte ranges, so
seeking stays cheap); intro detection writes only database rows, and an extracted subtitle is
uploaded into the owner's cache directory, where the owner serves it as if it had made it itself.
Nothing beyond correct `app.ister.server.url` values is needed for that, but the helper needs the
same tools as any node (ffmpeg, mkvextract, subtile-ocr) — use the same image.

An owner that should not spend its own CPU on a job family at all can hand it off entirely:

```properties
# on the owning node
app.ister.helper.offload-jobs=DETECT_SEGMENTS,SUBTITLES
```

Its queues for those jobs are still declared and filled, but only consumed by helpers. If no
helper is up, the work simply waits on the queue (visible as queue depth on the cluster page) —
nothing is lost, and nothing runs until a helper appears. Transcoding for the node's own cache
directory (podcast downloads) is never offloaded.

Watch the spelling of the `disks[n].name` values: they are used verbatim as queue names. Startup
looks each one up in the cluster's directories and logs a **warning** for a name it does not know
(it does not fail: the owning node may simply not be up yet). A misspelled name leaves the helper
listening on a queue nobody publishes to.

`app.ister.transcoder.disks[n].name` from earlier versions is still honoured (it maps to helper
disks with the `TRANSCODE` job only and, as before, replaces the node's own directories for
transcoding) and logs a deprecation warning; move it to `app.ister.helper.disks`.

## Worked example

`docker-compose-nodes-local.yml` in the repository runs a complete three-node cluster against
one database and broker:

- **server-1** — owns six directories (shows, movies and music over two disks)
- **server-2** — a second full node with its own disks
- **helper-1** — no directories, only `app.ister.helper.disks[n]` entries naming server-1's
  disks: it transcodes for server-1 and does its intro detection and subtitle OCR, which server-1
  offloads entirely (`APP_ISTER_HELPER_OFFLOAD_JOBS`)

All three nodes have VAAPI hardware acceleration enabled in the example — transcoding can land on
any of them, so hardware acceleration is worth configuring on every node that transcodes.

Points to copy from it: each node has its **own** `CACHE_DIR`, its own published port and a
`server.url` using a real LAN IP (not `localhost` — the other nodes must reach it), while
`APP_ISTER_CLUSTER_NAME` is the same everywhere.

## Operational notes

- The client's cluster page (Settings → Cluster) shows every node and its health — the fastest
  "is everything up?" check.
- Scans, metadata and cleanup run per node for the directories it owns; you trigger `scanLibraries`
  once and each node picks up its share.
- **Keep every node's clock NTP-disciplined.** Listen-along clients probe the unauthenticated
  `/time` endpoint to measure their clock offset, and device presence travels cluster-wide over the
  status exchange — since clients can talk to any node, the measured offset depends on which node
  answers. Nodes with drifting clocks make listen-along sync wobble.
- For the internals of cross-node transcoding, see the
  [architecture documentation](../../architecture/en/04-transcoding.md).

## Where to next

- [Search](06-search-typesense.md) — one Typesense serves the whole cluster
- [Maintenance](07-maintenance-and-troubleshooting.md) — per-node caches and jobs
