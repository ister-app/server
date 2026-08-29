---
description: Kennismaking met Ister, een zelfgehoste mediaserver voor films, series, muziek, boeken, strips en podcasts, inclusief de benodigde services en deploymentvormen.
---

# Inleiding

Ister is een zelfgehoste mediaserver in de geest van Plex en Jellyfin. Je wijst hem naar je media
op schijf, hij scant en verrijkt alles met metadata, en de [Ister-playerapps](https://github.com/ister-app/player)
streamen ervan. Deze documentatie is voor degene die de server **draait** — installeren,
configureren en gezond houden. Wil je weten hoe hij vanbinnen werkt, dan is er de
[architectuurdocumentatie](../../architecture/nl/00-overview.md).

## Wat je beheert

Een Ister-deployment bestaat uit een klein aantal services:

| Service | Verplicht? | Rol |
| --- | --- | --- |
| Ister-server | ja | De applicatie zelf: scannen, metadata, API's, streaming |
| PostgreSQL | ja | De enige bron van waarheid voor alle data |
| RabbitMQ | ja | Message broker; al het achtergrondwerk loopt erdoorheen |
| OIDC-provider | ja | Authenticatie — elke Keycloak-compatibele OpenID Connect-provider |
| Typesense | optioneel | Full-text zoeken door je libraries ([hoofdstuk 06](06-search-typesense.md)) |

Ister beheert zelf geen gebruikers: dat doet je OIDC-provider (bijvoorbeeld Keycloak). De server
valideert de JWT's die deze uitgeeft.

## Wat hij kan serveren

- **Films** en **tv-series** — metadata van TMDB, gestreamd als HLS met on-the-fly transcoding
- **Muziek** — metadata van MusicBrainz, albumhoezen, artiestafbeeldingen
- **Boeken** — epubs (te lezen in de client, inclusief EPUB 3-voorleesboeken) en luisterboeken,
  beide gekoppeld aan hetzelfde logische boek; metadata van Open Library en Wikidata
- **Comics** — CBZ, PDF en epub, georganiseerd per serie
- **Podcasts** — geabonneerd via RSS-feed, elk uur ververst, afleveringen op verzoek gedownload

## Deploymentvormen

- **Docker Compose** — de repository levert `docker-compose.yml` mee als referentiestack
  (database, RabbitMQ, een development-Keycloak, migraties, server). De
  [quick start](01-quick-start.md) loopt hem met je door; [Installatie](02-installation.md)
  beschrijft hoe je hem houdt.
- **Kubernetes** — de [chart-repository](https://github.com/ister-app/chart) biedt een Helm-chart
  en draait er in CI een volledige end-to-end-suite tegenaan.
- **Meerdere nodes** — meerdere servers kunnen samen één cluster vormen, elk met eigen schijven;
  transcodewerk draait op de node die het bestand heeft. Zie [Multi-node](05-multi-node.md).

Het productieartefact is een **GraalVM native image**: de gepubliceerde containerimage start in
een fractie van een seconde en bevat geen JVM. Normaal gesproken pull je gewoon
`ghcr.io/ister-app/server`; zelf bouwen staat beschreven in [Installatie](02-installation.md).

## Hoofdstukoverzicht

1. [Quick start](01-quick-start.md) — in een minuut of tien een draaiende server met testmedia waarop je kunt inloggen
2. [Installatie](02-installation.md) — vereisten, images, migraties, eerste start, health-endpoints
3. [Configuratie](03-configuration.md) — de volledige instellingenreferentie
4. [Libraries en media-indeling](04-libraries-and-media-layout.md) — hoe je bestanden op schijf organiseert
5. [Multi-node](05-multi-node.md) — een cluster draaien
6. [Zoeken (Typesense)](06-search-typesense.md) — full-text zoeken inschakelen en onderhouden
7. [Onderhoud en probleemoplossing](07-maintenance-and-troubleshooting.md) — geplande taken, back-up, monitoring
8. [Naamconventies](08-naming-conventions.md) — de exacte map- en bestandsnaamregels per librarytype
9. [Gebruikers, delen en toegang](09-users-sharing-and-access.md) — admins, zichtbaarheid per library, playback-sessies delen
