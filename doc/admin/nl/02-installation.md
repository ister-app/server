---
description: Installeer de zelfgehoste mediaserver Ister met Docker Compose of Kubernetes, van vereisten en containerimages tot eerste start en health-endpoints.
---

# Installatie

Dit hoofdstuk brengt je van "het werkt op mijn machine" ([Quick start](01-quick-start.md)) naar
een installatie die je houdt: wat de stack van de host nodig heeft, wat elk onderdeel doet, en
hoe je hem draait en upgradet. Alle voorbeelden gebruiken de containerimages; ze werken
identiek met Docker en Podman.

## Vereisten

- **Een containerruntime met Compose v2** (Docker, of Podman met `podman compose`/een recente
  `podman-compose`). Het compose-bestand leunt op
  `depends_on: condition: service_completed_successfully`, en dat ondersteunt Compose v1 niet.
- **x86_64-host.** De gepubliceerde native images worden gebouwd voor amd64.
- **Geheugen en CPU**: de server zelf is een GraalVM native image en blijft in rust ruim onder
  de 512 MB; PostgreSQL, RabbitMQ en (optioneel) Typesense en Keycloak komen daarbovenop. 4 GB
  RAM is een comfortabele ondergrens. Transcoderen is CPU-gebonden tenzij je
  [hardwareversnelling](03-configuration.md#transcoding) aanzet — reken je cores daarop.
- **Schijf**: naast je media (read-only is prima) heeft de server een persistente **cachemap**
  nodig (artwork, gedownloade podcastafleveringen) en een **transcode-tempmap** (HLS-segmenten;
  groeit met wat er gestreamd wordt, mag verloren gaan). Beide zijn volumes in het
  compose-bestand.
- **SELinux-hosts** (Fedora, RHEL): houd het `:Z`-achtervoegsel op de bind mounts, zoals het
  meegeleverde compose-bestand doet, anders kunnen de containers er niet bij.
- **Bestandsrechten**: de media-mount moet leesbaar zijn voor de containergebruiker; de cache-
  en tempmounts moeten schrijfbaar zijn.
- **Een OIDC-provider** voor alles voorbij uitproberen. Het compose-bestand bundelt een
  development-Keycloak; productie vraagt om je eigen provider — zie
  [de OIDC-sectie hieronder](#de-oidc-provider).

## De referentiestack

`docker-compose.yml` in de repository-root is de referentiedeployment:

| Service | Rol |
| --- | --- |
| `database` | PostgreSQL 18, de enige bron van waarheid (bind mount `./db-data/`) |
| `rabbitMQ` | Messagebroker; management-UI op poort 15672 |
| `keycloak` | Development-OIDC-provider met een vooraf geïmporteerde `Ister`-realm |
| `migrations` | Eenmalige Flyway-job; de server wacht tot die klaar is |
| `server` | De applicatie, op poort 8080 |

Kopieer hem, vul je eigen waarden in (zie [Configuratie](03-configuration.md)) en start hem:

```shell
docker compose up -d
```

De [quick start](01-quick-start.md) loopt dit bestand van begin tot eind door, inclusief
testmedia en een eerste scan. Twee zusterbestanden in de repository zijn ontwikkelhulpjes, geen
deploymentsjablonen: `docker-compose-local.yml` is het lokale-dev-bestand van de maintainer (de
zoekopzet die het raakt staat beschreven in [hoofdstuk 06](06-search-typesense.md)), en
`docker-compose-nodes-local.yml` is een uitgewerkt multi-node-voorbeeld
([hoofdstuk 05](05-multi-node.md)).

### De OIDC-provider

Ister beheert zelf geen gebruikers; het valideert JWT's van de provider die in `OIDC_URL` staat
([hoofdstuk 09](09-users-sharing-and-access.md)). De gebundelde Keycloak draait in
developmentmodus met een testaccount (`ister`/`ister`) en wildcard-redirect-URI's — prima om te
evalueren, niet voor een installatie waar anderen op inloggen. Voor productie: hard hem uit
(productiemodus, TLS, echte accounts, strakke redirect-URI's) of richt `OIDC_URL` op de
provider die je al draait. Wat je ook gebruikt, het moet zijn rollen in een `roles`-claim
zetten; `user` geeft toegang, `admin` geeft beheer — de details staan in
[hoofdstuk 09](09-users-sharing-and-access.md).

### Volumes die moeten blijven bestaan

- `./db-data/` — de database. Alles leeft hier; maak er back-ups van
  ([hoofdstuk 07](07-maintenance-and-troubleshooting.md)).
- `./cache-data/` (`CACHE_DIR`) — artwork en podcastdownloads. Raak je hem kwijt, dan wordt
  alles opnieuw bij de metadataproviders opgehaald.
- `./transcode-tmp/` (`TMP_DIR`) — HLS-segmenten. Mag tussen herstarts verloren gaan, maar moet
  schrijfbaar zijn en kan groeien terwijl er gestreamd wordt.
- `./media/` — je media, vrijwel alleen-lezen gemount; de server schrijft er niets in.

## De images

Twee images worden gepubliceerd op GHCR, altijd **in lockstep** getagd:

| Image | Doel |
| --- | --- |
| `ghcr.io/ister-app/server` | De server zelf (GraalVM native image, Fedora-basis met FFmpeg, mkvtoolnix en subtile-ocr inbegrepen) |
| `ghcr.io/ister-app/migrations` | Een Flyway-image met de databasemigraties |

Releases krijgen schone semver-tags (`2.0.0`); elke push naar `main` wordt daarnaast getagd met
de snapshot-versie (`2.0.1-SNAPSHOT`) en `main`. Pin de server- en migrations-images op
**dezelfde tag**, zodat schema en code nooit uit elkaar lopen.

## Databasemigraties

Het schema wordt beheerd door Flyway en migraties zijn **forward-only**. Je hebt twee opties:

- Draai de **migrations-image** voordat de server start (zoals het compose-bestand doet: de
  server heeft een `depends_on` op het voltooien van de migrations-container). Dit is het
  aanbevolen patroon — de Kubernetes-chart doet het ook zo.
- Laat de server migreren **bij het opstarten**: `spring.flyway.enabled=true` is de standaard,
  dus een server die tegen een verouderde database start, werkt deze zelf bij.

Hoe dan ook is upgraden: pull het nieuwe imagepaar, draai de migraties, start de server. De
server valideert het schema bij het opstarten (`ddl-auto=validate`) en weigert te booten tegen
een verkeerd schema — een luide fout, nooit stille corruptie.

## Eerste start

Bij elke boot brengt de server zijn configuratie in lijn met de database (`StartupTasks`):

- maakt of werkt zijn **node**-rij bij (naam, URL, cluster),
- maakt **libraries** en **directories** aan op basis van de `app.ister.disk.*`-configuratie,
- maakt de cachemappen op schijf aan,
- valideert de multi-node-configuratie en logt eventuele problemen.

Bij een verse installatie hoef je dus nergens doorheen te klikken: configureer je libraries
([hoofdstuk 04](04-libraries-and-media-layout.md)), start de server, log in via je
OIDC-provider en start een scan. Een library of directory later hernoemen in de configuratie
wordt bij de volgende start opgepikt.

## Health, metrics, logs

Management-endpoints luisteren op een **aparte poort, 8081**:

- `http://host:8081/actuator/health` — liveness/readiness
- `http://host:8081/actuator/metrics` en `/actuator/prometheus` — metrics, Prometheus-formaat

Houd 8081 intern; alleen poort 8080 hoeft bereikbaar te zijn voor clients. Logs gaan naar
stdout; verhoog de verbositeit met bijvoorbeeld `LOGGING_LEVEL_APP_ISTER=DEBUG`.

## Kubernetes

De [chart-repository](https://github.com/ister-app/chart) levert een Helm-chart die dezelfde
onderdelen uitrolt (migraties als init-job inbegrepen) en daar in CI een volledige
end-to-end-suite tegenaan draait. Draai je Kubernetes, begin daar dan in plaats van zelf het
compose-bestand te vertalen.

## Zelf images bouwen

Vanuit een checkout van de repository:

```shell
./gradlew nativeCompile                       # GraalVM native binary
docker build -f Dockerfile.native -t ister-server .
docker build -f Dockerfile.migrations -t ister-migrations .
```

`./gradlew bootBuildImage` bouwt via buildpacks een image op JVM-basis — prima om te testen,
maar de native image is wat productie draait. `Dockerfile.native` bakt bovendien FFmpeg met
VAAPI-drivers en Tesseract-taalpakketten voor ondertitel-OCR in, dus geef daar de voorkeur aan.

## Verder lezen

- [Configuratie](03-configuration.md) — alles wat je kunt (en zou moeten) instellen
- [Libraries en media-indeling](04-libraries-and-media-layout.md) — vóór je eerste scan
- [Gebruikers, delen en toegang](09-users-sharing-and-access.md) — je eigen OIDC-provider en admins
