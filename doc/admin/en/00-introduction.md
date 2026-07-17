# Introduction

Ister is a self-hosted media server in the spirit of Plex and Jellyfin. You point it at your media
on disk, it scans and enriches everything with metadata, and the [Ister player apps](https://github.com/ister-app/player)
stream from it. This documentation set is for the person **running** the server — installing it,
configuring it, and keeping it healthy. If you want to know how it works inside, the
[architecture documentation](../../architecture/en/00-overview.md) covers that.

## What you are operating

An Ister deployment is a small set of services:

| Service | Required? | Role |
| --- | --- | --- |
| Ister server | yes | The application itself: scanning, metadata, APIs, streaming |
| PostgreSQL | yes | The single source of truth for all data |
| RabbitMQ | yes | Message broker; all background work flows through it |
| OIDC provider | yes | Authentication — any Keycloak-compatible OpenID Connect provider |
| Typesense | optional | Full-text search across your libraries ([chapter 05](05-search-typesense.md)) |

Ister does not manage users itself: your OIDC provider (Keycloak, for example) does. The server
validates the JWTs it issues.

## What it can serve

- **Movies** and **TV shows** — metadata from TMDB, streamed as HLS with on-the-fly transcoding
- **Music** — metadata from MusicBrainz, cover art, artist images
- **Books** — epubs (read in the client, including EPUB 3 read-aloud books) and audiobooks,
  both attached to the same logical book; metadata from Open Library and Wikidata
- **Comics** — CBZ, PDF and epub, organised per series
- **Podcasts** — subscribed by RSS feed, refreshed hourly, episodes downloaded on demand

## Deployment shapes

- **Docker Compose** — the repository ships `docker-compose.yml` as a reference stack
  (database, RabbitMQ, migrations, server). See [Installation](01-installation.md).
- **Kubernetes** — the [chart repository](https://github.com/ister-app/chart) provides a Helm
  chart and runs a full end-to-end suite against it in CI.
- **Multiple nodes** — several servers can form one cluster, each owning its own disks; transcode
  work runs on the node that holds the file. See [Multi-node](04-multi-node.md).

The production artifact is a **GraalVM native image**: the published container image starts in a
fraction of a second and contains no JVM. You normally just pull `ghcr.io/ister-app/server`;
building it yourself is covered in [Installation](01-installation.md).

## Chapter map

1. [Installation](01-installation.md) — images, migrations, first start, health endpoints
2. [Configuration](02-configuration.md) — the full settings reference
3. [Libraries and media layout](03-libraries-and-media-layout.md) — how to organise files on disk
4. [Multi-node](04-multi-node.md) — running a cluster
5. [Search (Typesense)](05-search-typesense.md) — enabling and maintaining full-text search
6. [Maintenance and troubleshooting](06-maintenance-and-troubleshooting.md) — scheduled jobs, backup, monitoring
7. [Naming conventions](07-naming-conventions.md) — the exact directory and file naming rules per library type
