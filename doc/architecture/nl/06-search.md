---
description: "Hoe de optionele Typesense-zoekmodule van Ister indexeert: runtime-toggle, index-updates via events, reindexen met alias-swap en het meertalige schema."
---

# Zoeken (Typesense)

Full-text search is een optionele module (`search/`, package `app.ister.search`), gedreven door
Typesense. Zie het [zoekflow-diagram](../diagrams/search-flow.md).

## Runtime-toggle, geen bean-condition

De enabled-vlag (`app.ister.typesense.enabled`) wordt **op runtime in de handlers** gecheckt — bij
disabled worden indexevents geconsumeerd en genegeerd, zoals de TMDB-key-check. Het is bewust
*niet* `@ConditionalOnProperty`: bean-conditions worden bij de GraalVM native-image build bevroren,
dus een conditionele bean zou volledig uit het productie-image gebakken worden ([hoofdstuk
8](08-native-image-and-testing.md)). Het properties-bestand van de module wordt geïmporteerd via
`spring.config.import` en heeft daarom een entry nodig in
`search/src/main/resources/META-INF/native-image/resource-config.json`.

## Index-updates

Alles loopt via `SEARCH_INDEX_REQUESTED` (`entityType`, `entityId`, `action`), afgehandeld door
`HandleSearchIndexRequested`, dat de entiteit laadt, naar een document mapt en dat upsert (of
verwijdert) in de collection achter de `media`-alias.

Verzenders:

- **Creatie** komt gratis mee: `ServerEventService.createXFoundEvent` stuurt bij creatie een
  indexevent voor elk doorzoekbaar entiteitstype (`SearchEntityType`: movie, show, episode, person,
  album, track, book, podcast — comicvolumes indexeren als BOOK).
- **Verrijkings**-handlers sturen na hun metadata-saves: `MetadataSave` (TMDB), de
  worker-`HandlePersonFound`/`HandleAlbumFound`/`HandleBookFound` (MusicBrainz / Open Library), de
  disk-`HandlePersonFound`/`HandleAlbumFound`, `PersonLookupService` (TMDB-cast),
  `HandleNfoFileFound`, `HandleAudioFileFound` (audio-tags, inclusief `action=DELETE` bij
  track-dedup), `HandleEpubFileFound`, `HandleComicFileFound`, `HandlePodcastRefreshRequested`,
  plus `ScannerHelperService` (boekcreatie uit de mappenstructuur) en `BookSeriesService`
  (reekstoewijzing herindexeert de betrokken boeken).
- **Deletes**: elke code die een doorzoekbare entiteit verwijdert moet
  `serverEventService.createSearchDeleteEvent(...)` aanroepen — `PodcastController` doet dat
  bijvoorbeeld bij het opzeggen van een abonnement. Er zijn vangnetten — de
  upsert-handler verwijdert het document als de entiteit niet meer bestaat, en een reindex bouwt
  alles opnieuw op — maar deze regel houdt de index correct tússen reindexen in.

## Volledige reindex

De GraphQL-mutation `rebuildSearchIndex` stuurt `SEARCH_REINDEX_REQUESTED`. De handler maakt een verse
collection (`media_v<timestamp>`), pagineert door alle entiteiten en importeert ze, en **zet dan de
alias om** en dropt oude collections — zoeken blijft live tijdens de rebuild.

Een databasemigratie die entiteiten achter de index om verwijdert of hernoemt — bijvoorbeeld de
samenvoeging van dubbele artiesten in `V43` — laat verouderde persoonsdocumenten achter; draai
`rebuildSearchIndex` daarom één keer na een upgrade voorbij die migratie.

## Meertalig schema

Het collection-schema en de `query_by`-lijst worden gegenereerd uit `LanguageProperties.tags()`:
elke geconfigureerde taal krijgt `title_<tag>`-, `description_<tag>`- en `genre_<tag>`-velden met de
bijpassende Typesense-`locale`. `SearchDocument` is een `Map<String,Object>` in plaats van een vast
record, precies zodat de gelokaliseerde keys dynamisch blijven.

Omdat het schema vastligt bij het aanmaken van de collection, kost **een taal toevoegen twee
stappen**: een re-scan/analyze (om de nieuwe `MetadataEntity`-rijen aan te maken, zie [hoofdstuk
3](03-media-types-and-metadata.md)) gevolgd door `rebuildSearchIndex`.

## Query'en

De GraphQL-query `search(term)` bevraagt Typesense over alle gelokaliseerde velden en geeft een
`SearchResult`-union terug die uit PostgreSQL gehydrateerd wordt — Typesense bepaalt alleen *welke*
ids matchen; de gezaghebbende data komt altijd uit de database.
