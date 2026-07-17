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
  indexevent voor elk doorzoekbaar entiteitstype (movie, show, episode, person, album, track).
- **Verrijkings**-handlers sturen na hun metadata-saves: `MetadataSave` (TMDB), de
  worker-`HandlePersonFound`/`HandleAlbumFound` (MusicBrainz), `PersonLookupService` (TMDB-cast),
  `HandleNfoFileFound` en `HandleAudioFileFound` (audio-tags, inclusief `action=DELETE` bij
  track-dedup).
- **Deletes**: elke code die een doorzoekbare entiteit verwijdert moet
  `serverEventService.createSearchDeleteEvent(...)` aanroepen. Er zijn vangnetten — de
  upsert-handler verwijdert het document als de entiteit niet meer bestaat, en een reindex bouwt
  alles opnieuw op — maar deze regel houdt de index correct tússen reindexen in.

## Volledige reindex

De GraphQL-mutation `reindexSearch` stuurt `SEARCH_REINDEX_REQUESTED`. De handler maakt een verse
collection (`media_v<timestamp>`), pagineert door alle entiteiten en importeert ze, en **zet dan de
alias om** en dropt oude collections — zoeken blijft live tijdens de rebuild.

## Meertalig schema

Het collection-schema en de `query_by`-lijst worden gegenereerd uit `LanguageProperties.tags()`:
elke geconfigureerde taal krijgt `title_<tag>`-, `description_<tag>`- en `genre_<tag>`-velden met de
bijpassende Typesense-`locale`. `SearchDocument` is een `Map<String,Object>` in plaats van een vast
record, precies zodat de gelokaliseerde keys dynamisch blijven.

Omdat het schema vastligt bij het aanmaken van de collection, kost **een taal toevoegen twee
stappen**: een re-scan/analyze (om de nieuwe `MetadataEntity`-rijen aan te maken, zie [hoofdstuk
3](03-media-types-and-metadata.md)) gevolgd door `reindexSearch`.

## Query'en

De GraphQL-query `search(term)` bevraagt Typesense over alle gelokaliseerde velden en geeft een
`SearchResult`-union terug die uit PostgreSQL gehydrateerd wordt — Typesense bepaalt alleen *welke*
ids matchen; de gezaghebbende data komt altijd uit de database.
