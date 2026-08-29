---
description: Try the Ister media server in about ten minutes — a copy-paste Docker Compose quick start with a bundled Keycloak, a generated test video, and a first scan.
---

# Quick start

This chapter gets you from nothing to a running, signed-in server with one scanned show in
about ten minutes, using the reference `docker-compose.yml` from the repository root. It bundles
everything, including a development Keycloak with a ready-made realm — you bring only a TMDB key
(optional) and, if you want a test video, ffmpeg.

This is a **try-it-out** setup: development identity provider, default passwords, wildcard
redirect URIs. When you like what you see, [Installation](02-installation.md) covers hardening
it into something you keep.

## 1. Prerequisites

- **Docker or Podman with Compose v2.** The compose file uses
  `depends_on: condition: service_completed_successfully`, which older Compose v1 does not
  understand. `docker compose version` (or `podman-compose --version`) should work.
- **A TMDB API key (optional but recommended).** Create an account on
  [themoviedb.org](https://www.themoviedb.org/) and copy the **API Read Access Token** from
  [Settings → API](https://www.themoviedb.org/settings/api). Without it everything still runs,
  but shows and movies stay without descriptions and artwork.
- **ffmpeg on the host (optional)** — only used below to generate a test video. Skip it if you
  will point the server at real media instead.

## 2. Get the stack and configure it

Clone the repository (or download just `docker-compose.yml` and `keycloak/Ister-realm.json`,
keeping the same relative layout):

```shell
git clone https://github.com/ister-app/server.git
cd server
```

Open `docker-compose.yml` and paste your TMDB token into `APP_ISTER_SERVER_TMDB_APIKEY`
(or leave it empty to skip metadata).

One line of host configuration is needed: the OIDC issuer URL must look the same to the server
container and to your browser, and both use `http://keycloak:8060`. Point that hostname at
localhost in `/etc/hosts`:

```shell
echo "127.0.0.1 keycloak" | sudo tee -a /etc/hosts
```

## 3. Create a test show (optional)

The scanner picks media up from directory and file names
([naming conventions](08-naming-conventions.md)). Generate a three-second test episode:

```shell
mkdir -p "media/shows/Test Show (2024)/Season 01"
ffmpeg -f lavfi -i color=size=1280x720:rate=25:color=yellow \
  -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100 \
  -map 0 -map 1 -metadata:s:a:0 language=eng -t 3 \
  "media/shows/Test Show (2024)/Season 01/Test Show - s01e01.mkv"
```

Real media works the same way: anything under `./media/shows/` is scanned as the `shows`
library.

## 4. Start it

```shell
docker compose up -d
```

First start pulls the images, runs the Flyway migrations, imports the Keycloak realm and boots
the server. Wait for the health endpoint to report `UP` (the server publishes management
endpoints on port 8081 inside the compose network only; expose it by adding `- "8081:8081"` to
the server's ports if you want to curl it from the host):

```shell
curl -s http://localhost:8080/.well-known/ister
```

A JSON answer means the server is up.

The bundled realm contains one test account, **`ister` / `ister`**, which carries both the
`user` and the `admin` role. The Keycloak admin console itself is on
[http://keycloak:8060](http://keycloak:8060) (`admin` / `admin`).

## 5. Scan and verify — no client needed

Get a token for the test user (the `ister` client has direct-access grants enabled, so a
password grant from curl works):

```shell
TOKEN=$(curl -s -d 'grant_type=password&client_id=ister&username=ister&password=ister' \
  http://keycloak:8060/realms/Ister/protocol/openid-connect/token | \
  python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
```

Trigger a scan of all libraries (an admin-only mutation):

```shell
curl -s http://localhost:8080/graphql \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"mutation { scanLibraries }"}'
```

Scanning is asynchronous — it flows through RabbitMQ, and with a TMDB key the metadata fetch
takes a few extra seconds. Then list what was found:

```shell
curl -s http://localhost:8080/graphql \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"query { shows { totalElements content { name releaseYear } } }"}'
```

`"totalElements":1` with your test show in `content` means the whole pipeline — scanner,
RabbitMQ, database, auth — works.

## 6. Watch it in the player

Install an [Ister player app](https://github.com/ister-app/player), point it at
`http://localhost:8080`, and sign in as `ister` / `ister` through the bundled Keycloak.
Playback transcodes on the fly to HLS; the first segments of a stream take a few seconds.

## Where to next

- Real media and more library types (movies, music, books, comics, podcasts) —
  [Libraries and media layout](04-libraries-and-media-layout.md)
- Keeping this stack: pinned image tags, backups, health monitoring —
  [Installation](02-installation.md) and [Maintenance](07-maintenance-and-troubleshooting.md)
- Replacing the development Keycloak with your own OIDC provider, and how the `admin` role
  works — [Users, sharing, and access](09-users-sharing-and-access.md)
- Everything you can configure — [Configuration](03-configuration.md)
