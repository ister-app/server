# Installation

This chapter takes you from nothing to a running server. All examples use the container images;
they work identically with Docker and Podman.

## The images

Two images are published to GHCR, always tagged **in lockstep**:

| Image | Purpose |
| --- | --- |
| `ghcr.io/ister-app/server` | The server itself (GraalVM native image, Fedora base with FFmpeg, mkvtoolnix and subtile-ocr included) |
| `ghcr.io/ister-app/migrations` | A Flyway image carrying the database migrations |

Releases get clean semver tags (`2.0.0`); every push to `main` is additionally tagged with the
snapshot version (`2.0.1-SNAPSHOT`) and `main`. Pin the server and migrations images to the
**same tag** so schema and code never drift apart.

## Reference stack

`docker-compose.yml` in the repository root is the reference deployment: PostgreSQL 18,
RabbitMQ (with management UI on port 15672), the migrations job, and the server on port 8080.
Copy it, fill in your own values (see [Configuration](02-configuration.md)), and start it:

```shell
docker compose up -d
```

Two sibling files are worth knowing: `docker-compose-local.yml` adds Typesense for search, and
`docker-compose-nodes-local.yml` is a worked multi-node example ([chapter 04](04-multi-node.md)).

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
([chapter 03](03-libraries-and-media-layout.md)), start the server, sign in through your OIDC
provider, and trigger a scan. Renaming a library or directory in config later is picked up on
the next start.

## Health, metrics, logs

Management endpoints listen on a **separate port, 8081**:

- `http://host:8081/actuator/health` — liveness/readiness
- `http://host:8081/actuator/metrics` and `/actuator/prometheus` — metrics, Prometheus format

Keep 8081 internal; only port 8080 needs to be reachable by clients. Logs go to stdout; raise
verbosity with e.g. `LOGGING_LEVEL_APP_ISTER=DEBUG`.

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

- [Configuration](02-configuration.md) — everything you can (and should) set
- [Libraries and media layout](03-libraries-and-media-layout.md) — before your first scan
