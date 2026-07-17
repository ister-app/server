# Installatie

Dit hoofdstuk brengt je van niets naar een draaiende server. Alle voorbeelden gebruiken de
containerimages; ze werken identiek met Docker en Podman.

## De images

Twee images worden gepubliceerd op GHCR, altijd **in lockstep** getagd:

| Image | Doel |
| --- | --- |
| `ghcr.io/ister-app/server` | De server zelf (GraalVM native image, Fedora-basis met FFmpeg, mkvtoolnix en subtile-ocr inbegrepen) |
| `ghcr.io/ister-app/migrations` | Een Flyway-image met de databasemigraties |

Releases krijgen schone semver-tags (`2.0.0`); elke push naar `main` wordt daarnaast getagd met
de snapshot-versie (`2.0.1-SNAPSHOT`) en `main`. Pin de server- en migrations-images op
**dezelfde tag**, zodat schema en code nooit uit elkaar lopen.

## Referentiestack

`docker-compose.yml` in de repository-root is de referentiedeployment: PostgreSQL 18,
RabbitMQ (met management-UI op poort 15672), de migratiejob en de server op poort 8080.
Kopieer hem, vul je eigen waarden in (zie [Configuratie](02-configuration.md)) en start hem:

```shell
docker compose up -d
```

Twee zusterbestanden zijn goed om te kennen: `docker-compose-local.yml` voegt Typesense toe voor
zoeken, en `docker-compose-nodes-local.yml` is een uitgewerkt multi-node-voorbeeld
([hoofdstuk 04](04-multi-node.md)).

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
([hoofdstuk 03](03-libraries-and-media-layout.md)), start de server, log in via je
OIDC-provider en start een scan. Een library of directory later hernoemen in de configuratie
wordt bij de volgende start opgepikt.

## Health, metrics, logs

Management-endpoints luisteren op een **aparte poort, 8081**:

- `http://host:8081/actuator/health` — liveness/readiness
- `http://host:8081/actuator/metrics` en `/actuator/prometheus` — metrics, Prometheus-formaat

Houd 8081 intern; alleen poort 8080 hoeft bereikbaar te zijn voor clients. Logs gaan naar
stdout; verhoog de verbositeit met bijvoorbeeld `LOGGING_LEVEL_APP_ISTER=DEBUG`.

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

- [Configuratie](02-configuration.md) — alles wat je kunt (en zou moeten) instellen
- [Libraries en media-indeling](03-libraries-and-media-layout.md) — vóór je eerste scan
