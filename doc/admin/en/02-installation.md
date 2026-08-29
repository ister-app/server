---
description: Install the Ister self-hosted media server with Docker Compose or Kubernetes, from prerequisites and container images to first start and health endpoints.
---

# Installation

This chapter takes you from "it works on my machine" ([Quick start](01-quick-start.md)) to an
installation you keep: what the stack needs from the host, what each piece does, and how to run
and upgrade it. All examples use the container images; they work identically with Docker and
Podman.

## Prerequisites

- **A container runtime with Compose v2** (Docker, or Podman with `podman compose`/a recent
  `podman-compose`). The compose file relies on
  `depends_on: condition: service_completed_successfully`, which Compose v1 does not support.
- **x86_64 host.** The published native images are built for amd64.
- **Memory and CPU**: the server itself is a GraalVM native image and idles well under 512 MB;
  PostgreSQL, RabbitMQ and (optionally) Typesense and Keycloak come on top. 4 GB RAM is a
  comfortable floor. Transcoding is CPU-bound unless you enable
  [hardware acceleration](03-configuration.md#transcoding) — budget cores accordingly.
- **Disk**: besides your media (read-only is fine), the server needs a persistent **cache
  directory** (artwork, downloaded podcast episodes) and a **transcode temp directory** (HLS
  segments; sized by what people stream, safe to lose). Both are volumes in the compose file.
- **SELinux hosts** (Fedora, RHEL): keep the `:Z` suffix on the bind mounts, as the shipped
  compose file does, or the containers cannot read them.
- **File permissions**: the media mount must be readable by the container user; the cache and
  temp mounts must be writable.
- **An OIDC provider** for anything beyond trying it out. The compose file bundles a
  development Keycloak; production wants your own provider — see
  [the OIDC section below](#the-oidc-provider).

## The reference stack

`docker-compose.yml` in the repository root is the reference deployment:

| Service | Role |
| --- | --- |
| `database` | PostgreSQL 18, the single source of truth (bind mount `./db-data/`) |
| `rabbitMQ` | Message broker; management UI on port 15672 |
| `keycloak` | Development OIDC provider with a pre-imported `Ister` realm |
| `migrations` | One-shot Flyway job; the server waits for it to complete |
| `server` | The application, on port 8080 |

Copy it, fill in your own values (see [Configuration](03-configuration.md)), and start it:

```shell
docker compose up -d
```

The [quick start](01-quick-start.md) walks through this file end to end, including test media
and a first scan. Two sibling files in the repository are development aids, not deployment
templates: `docker-compose-local.yml` is the maintainer's local-dev file (the search setup it
touches is documented in [chapter 06](06-search-typesense.md)), and
`docker-compose-nodes-local.yml` is a worked multi-node example ([chapter 05](05-multi-node.md)).

### The OIDC provider

Ister does not manage users; it validates JWTs from the provider configured in `OIDC_URL`
([chapter 09](09-users-sharing-and-access.md)). The bundled Keycloak runs in development mode
with a test account (`ister`/`ister`) and wildcard redirect URIs — fine for evaluating, not for
an installation others sign in to. For production, either harden it (production mode, TLS, real
accounts, tight redirect URIs) or point `OIDC_URL` at the provider you already run. Whatever
you use must put its roles in a `roles` claim; `user` grants access, `admin` grants
administration — the details are in [chapter 09](09-users-sharing-and-access.md).

### Volumes that must persist

- `./db-data/` — the database. Everything lives here; back it up
  ([chapter 07](07-maintenance-and-troubleshooting.md)).
- `./cache-data/` (`CACHE_DIR`) — artwork and podcast downloads. Losing it means re-fetching
  everything from the metadata providers.
- `./transcode-tmp/` (`TMP_DIR`) — HLS segments. Safe to lose between restarts, but it must be
  writable and can grow while people stream.
- `./media/` — your media, mounted read-mostly; the server writes nothing into it.

## The images

Two images are published to GHCR, always tagged **in lockstep**:

| Image | Purpose |
| --- | --- |
| `ghcr.io/ister-app/server` | The server itself (GraalVM native image, Fedora base with FFmpeg, mkvtoolnix and subtile-ocr included) |
| `ghcr.io/ister-app/migrations` | A Flyway image carrying the database migrations |

Releases get clean semver tags (`2.0.0`); every push to `main` is additionally tagged with the
snapshot version (`2.0.1-SNAPSHOT`) and `main`. Pin the server and migrations images to the
**same tag** so schema and code never drift apart.

## Database migrations

The schema is managed by Flyway and migrations are **forward-only**. You have two options:

- Run the **migrations image** before the server starts (as the compose file does: the server
  `depends_on` the migrations container completing). This is the recommended pattern — it is also
  how the Kubernetes chart does it.
- Let the server migrate **on boot**: `spring.flyway.enabled=true` is the default, so a server
  started against an outdated database brings it up to date itself.

Either way, upgrading is: pull the new image pair, run migrations, start the server. The server
validates the schema at startup (`ddl-auto=validate`) and refuses to boot against a wrong one —
a loud failure, never silent corruption.

## First start

On every boot the server reconciles its configuration with the database (`StartupTasks`):

- creates or updates its **node** row (name, URL, cluster),
- creates **libraries** and **directories** from the `app.ister.disk.*` configuration,
- creates the cache directories on disk,
- validates the multi-node configuration and logs any problems.

So on a fresh install there is nothing to click through: configure your libraries
([chapter 04](04-libraries-and-media-layout.md)), start the server, sign in through your OIDC
provider, and trigger a scan. Renaming a library or directory in config later is picked up on
the next start.

## Health, metrics, logs

Management endpoints listen on a **separate port, 8081**:

- `http://host:8081/actuator/health` — liveness/readiness
- `http://host:8081/actuator/metrics` and `/actuator/prometheus` — metrics, Prometheus format

Keep 8081 internal; only port 8080 needs to be reachable by clients. Logs go to stdout; raise
verbosity with e.g. `LOGGING_LEVEL_APP_ISTER=DEBUG`.

## Kubernetes

The [chart repository](https://github.com/ister-app/chart) provides a Helm chart that deploys
the same pieces (migrations as an init job included) and runs a full end-to-end suite against
it in CI. If you run Kubernetes, start there rather than translating the compose file yourself.

## Building images yourself

From a repository checkout:

```shell
./gradlew nativeCompile                       # GraalVM native binary
docker build -f Dockerfile.native -t ister-server .
docker build -f Dockerfile.migrations -t ister-migrations .
```

`./gradlew bootBuildImage` builds a JVM-based image via buildpacks — fine for testing, but the
native image is what production runs. `Dockerfile.native` also bakes in FFmpeg with VAAPI
drivers and Tesseract language packs for subtitle OCR, so prefer it.

## Where to next

- [Configuration](03-configuration.md) — everything you can (and should) set
- [Libraries and media layout](04-libraries-and-media-layout.md) — before your first scan
- [Users, sharing, and access](09-users-sharing-and-access.md) — your own OIDC provider and admins
