---
description: "Serve media from S3-compatible object storage (AWS S3, MinIO, Garage, Ceph): S3 library directories attached to several nodes, and the optional cluster-shared cache and transcode stores."
---

# Object storage (S3)

A library directory does not have to be a disk. Any S3-compatible bucket — AWS S3 or a self-hosted
MinIO, Garage or Ceph RGW — can hold a library, and one S3 directory can be served by **several
nodes at once**: every node that configures it scans, analyses, transcodes and streams it. On top
of that, the derived files (artwork, extracted subtitles, podcast downloads) and the HLS transcode
output can be moved into the bucket too, so every node serves the same cache and the same segments.

Three things are independent and each optional:

| Feature | Property | What it does |
| --- | --- | --- |
| S3 library directory | `app.ister.disk.directories[n].s3-connection` | media in a bucket prefix, attached to N nodes |
| Shared cache | `app.ister.server.cache-s3-connection` | artwork, subtitles, podcast downloads in the bucket for **every** library on this node |
| Shared transcode store | `app.ister.server.tmp-s3-connection` | HLS playlists and segments published to the bucket, read back by every node |

## Connections

Credentials and endpoints live in named connections, in config only — never in the database:

```properties
app.ister.s3.connections[0].name=minio
app.ister.s3.connections[0].endpoint=http://minio:9000   # empty = AWS
app.ister.s3.connections[0].region=us-east-1
app.ister.s3.connections[0].bucket=ister
app.ister.s3.connections[0].path-style=true             # required by most self-hosted servers
app.ister.s3.connections[0].access-key=…
app.ister.s3.connections[0].secret-key=…
```

Environment variables work as usual (`APP_ISTER_S3_CONNECTIONS_0_NAME`, …). One connection is one
bucket; a second bucket is a second connection. Leave the endpoint empty for AWS and the SDK's
regular credential chain (instance profile, env vars, `~/.aws`) is used when the keys are empty.

## An S3 library directory

Instead of a `path`, a directory names a connection and, optionally, a key prefix:

```properties
app.ister.disk.directories[1].name=shows-s3
app.ister.disk.directories[1].library=shows
app.ister.disk.directories[1].s3-connection=minio
app.ister.disk.directories[1].prefix=media/shows
```

The objects under the prefix follow the same layout rules as a disk ([Libraries and media
layout](04-libraries-and-media-layout.md), [Naming conventions](08-naming-conventions.md)); the
"directories" are just key segments. Rows record the object as `s3://bucket/key`.

### Attaching nodes

An S3 directory has **no owning node**. The first node that starts with it creates it; every other
node that lists the **same name with the same connection and prefix** is attached to it and takes
its share of the work — all attached nodes consume the directory's queues, and RabbitMQ hands each
message to one of them. A scan is started once and runs on whichever node picks it up (a database
lock keeps two overlapping scans apart). A node whose entry points the same name at another bucket
or prefix refuses to start, and so does a node configuring a local `path` for a name the cluster
knows as S3.

Clients can stream an S3 file from any attached node. The node reads the object with byte ranges
and proxies it — S3 never has to be reachable from the client, and by default not from ffmpeg
either: ffmpeg reads through the node's own `/mediaFile/{id}/download` over the loopback address.
`app.ister.s3.ffmpeg-direct=true` hands ffmpeg presigned S3 URLs instead, which saves the hop
through the node at the price of exposing the bucket to the transcoding hosts.

Helper nodes ([Multi-node](05-multi-node.md)) are not needed for S3 directories: attaching a node
*is* the way to add capacity. A helper that lists an S3 directory under `app.ister.helper.disks`
reads it through an attached node, like it does for a disk.

Things that need a real file — epub and comic readers, PDF rendering — download the
object once into `app.ister.s3.local-copy-dir` (under the tmp dir by default) and keep it as an LRU
cache capped by `app.ister.s3.local-copy-max-bytes` (2 GiB).

## Shared cache

```properties
app.ister.server.cache-s3-connection=minio
app.ister.server.cache-s3-prefix=cache
```

With this set, the node writes every derived file into `s3://bucket/cache/…` instead of its
`CACHE_DIR`: episode stills, downloaded posters, embedded and epub covers, extracted `.srt` files,
podcast downloads. It applies to **all** libraries on the node, disks included. The directory is
registered once as `<cluster name>-s3-cache` and every node configured with it is attached, so
podcast downloads and cache-scoped events are shared work as well.

The node-local cache directory is not migrated: its rows keep being served by the node that owns
them, only new files go to the bucket. The daily cache cleanup sweeps the shared cache too (one node
per run, decided by a database lock), honouring the same dry-run flag.

## Shared transcode store

```properties
app.ister.server.tmp-s3-connection=minio
app.ister.server.tmp-s3-prefix=tmp
```

Encoding still happens on the local `TMP_DIR` of the node running the pass — ffmpeg's segment
muxer wants a filesystem — but every finished segment, playlist and completion marker is published
to `s3://bucket/tmp/{mediaFileId}/…`, and a node serving playback that misses a file locally reads
it through from there. So a client can start playback on node A while node B runs the pass, and a
file transcoded yesterday on any node plays instantly on every node today. The node-to-node segment
upload of the disk-based multi-node setup is not used when this is on.

The store is swept with the local tmp cleanup (same schedule, `min-age` and dry-run): a media
file's published directory goes when the file is gone or when nothing was published for it within
the window.

## Local development

`docker-compose-local.yml` ships a MinIO (`minio`, console on port 9001, `minioadmin`/`minioadmin`)
plus a one-shot that creates the `ister` bucket; the commented environment block on the `server`
service points a library at it. The integration tests run the same server through Testcontainers.

## Where to next

- [Multi-node](05-multi-node.md) — attached nodes next to owning nodes and helpers
- [Configuration](03-configuration.md#object-storage-s3) — every property in one table
