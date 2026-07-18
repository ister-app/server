# Mediatypes en metadata

Elk mediatype volgt hetzelfde patroon: de scanner registreert entities op basis van het pad,
`*_FOUND`-events triggeren verrijking vanuit een externe provider, afbeeldingen lopen via
`IMAGE_FOUND`. Metadata-providers staan in `worker/.../events/`; hun HTTP-clients zijn
gecentraliseerd in `worker/.../http/MetadataRestClients`, en **elke externe base-URL is een
property** met de echte service als default — de e2e van de chart serveert ze allemaal vanuit één
WireMock-pod en faalt op dead-lettered events, dus een hardcoded URL breekt de CI.

## Talen

`app.ister.languages` / `ISTER_LANGUAGES` (ISO-639-1-tags, default `en,nl`, ontsloten als
`LanguageProperties`) is dé app-brede talenlijst. De movie/show/episode-handlers halen TMDB-details
**één keer per geconfigureerde tag** op, wat per taal per item één `MetadataEntity`-rij oplevert; de
taal wordt opgeslagen als **ISO-639-3**, ook al gebruikt de fetch ISO-639-1. De eerste tag is de
primaire/fallback-taal. Dezelfde lijst stuurt het zoekschema aan ([hoofdstuk 6](06-search.md)). Een
taal toevoegen vereist een re-scan plus een reindex.

## Films, shows, afleveringen (TMDB)

De `MOVIE_FOUND`- / `SHOW_FOUND`- / `EPISODE_FOUND`-handlers halen TMDB-details per taal op, slaan
`MetadataEntity`-rijen op en downloaden posters/achtergronden (verstuurd als `IMAGE_FOUND` op de
cache-directory).

Credits komen in dezelfde pass mee: movie credits, show aggregate credits en episode credits (cast
+ guest stars) worden `PersonEntity`- + `CreditEntity`-rijen, direct in de database geschreven. Een
`CreditEntity` koppelt een persoon aan precies één van movie/show/episode. Een `PersonEntity` wordt
gedeeld tussen acteurs en muziekartiesten; TMDB-castleden worden gededupliceerd tegen bestaande
personen op **exacte naam + geboortejaar**. Het GraphQL-type `Credit` ontsluit de terugverwijzingen
(`movie`/`show`/`episode`, batch-resolved in `CreditController`), zodat een filmografie opvraagbaar
is via `personById { credits { movie/show/episode } }`.

## Muziek (MusicBrainz)

Artiest-directories worden `PersonEntity`-rijen (`PERSON_FOUND`), albums `AlbumEntity`
(`ALBUM_FOUND`), tracks lopen via `AUDIO_FILE_FOUND` (ffprobe + ID3-tags + embedded cover).
Album-identiteit komt uit het **pad**, nooit uit tags. De worker-`HandleAlbumFound` bevraagt
MusicBrainz en downloadt de release-group-cover; de disk-kant
(`HandlePersonFound`/`HandleAlbumFound`) zoekt naar `artist.nfo`/`album.nfo`. Artiesten krijgen een
`birthYear` (MusicBrainz life-span, of de mapnaam) — precies zodat de TMDB-acteur-dedup hierboven ze
kan matchen.

## Biografieën en portretten (Wikipedia/Wikidata)

`WikipediaService` (worker) verrijkt personen met meertalige biografieën en portretten: Wikidata
resolveert de entiteit en zijn afbeelding/sitelinks, het Wikipedia-summary-endpoint (een
URL-template-property) levert de extracts per taal. Dezelfde service voedt de beschrijvingen van
stripseries.

## Boeken (`LibraryType.BOOK`)

De directorygrammatica is auteur-eerst: `Author/Book.epub` en `Author/Book/NNN_Chapter.mp3`. Alle
formaten van één (genormaliseerde) boeknaam convergeren op één `BookEntity` (auteur =
`PersonEntity`); formaten zijn bijlagen — epubs koppelen via `MediaFileEntity.bookEntity`,
audiobook-mp3's via `ChapterEntity` (gestreamd over hetzelfde audio-only HLS-pad als tracks).
`HandleAudioFileFound` brancht op library-type en maakt chapters in plaats van tracks.

- **Media overlays** (EPUB 3-voorleesaudio) worden gemarkeerd op `MediaFileEntity.mediaOverlays`,
  uitsluitend gedetecteerd uit de **inhoud** van de epub (SMIL-entries in het OPF-manifest, geparst
  door `disk/.../epub/EpubParser`) — nooit uit de bestandsnaam.
- `BOOK_FOUND` triggert **Open Library**-verrijking (beschrijving, en een cover alleen als er nog
  geen is); **Wikidata** voegt reekslidmaatschap toe (reeksnaam + positie); NFO-data wordt
  gededupliceerd tegen provider-data, zodat re-scans de beschrijvingen niet verdubbelen.
- **Reeksen** (`BookSeriesService`, core) komen uit drie bronnen met vaste voorrang:
  epub-reeksmetadata (calibre / EPUB 3 belongs-to-collection) is autoritatief en herschrijft de
  koppeling bij elke scan; een pad-prefix-heuristiek vult reeksloze boeken wanneer ≥2 boeken van de
  auteur de prefix vóór een ` - `/`: `-scheider delen; en **Wikidata-reeksontdekking**
  (`WikidataBookSeriesService.discoverSeries`, gedraaid vanuit `BOOK_FOUND`) koppelt een reeksloos
  boek aan een van de *bestaande* reeksen van de auteur via zijn P179-statement (part of series) —
  ze maakt nooit een reeks aan, en vereist een P50-labelmatch (auteur) zodat de gelijknamige film of
  game nooit kan koppelen. Ontdekking dekt wat de andere twee niet zien: titels zonder scheider
  ("Harry Potter en de steen der wijzen") en audiobook-only boeken zonder epub-metadata. Wanneer
  epub-metadata een reeks *aanmaakt*, wordt `BOOK_FOUND` eenmalig opnieuw afgevuurd voor de
  reeksloze boeken van de auteur, zodat ontdekking binnen één scan convergeert ongeacht de
  scanvolgorde.
- Epubs worden door de client lazy gelezen via `GET /epub/{mediaFileId}/resource/{entry}`
  ([hoofdstuk 7](07-api-and-auth.md)); de leespositie is een `WatchStatusEntity` met
  `readingLocation` (epubcfi) + `readingProgress`.

## Strips (`LibraryType.COMIC`, migratie V23)

Strips zijn **serie-eerst**, het omgekeerde van de boekengrammatica: `{root}/{Series Name (start
year)}/Volume 27.cbz` (ook `Vol 3 - Subtitle.pdf`, `Issue 8.epub`, en getolereerde wilde patronen;
`cover.jpg` in de serie-directory is serie-artwork). Het `(YYYY)`-suffix is het **startjaar** van de
serie, geen auteursjaar — strips hebben geen auteur in het pad; het parsen zit in `ComicPathObject`
+ `ComicFileNameParser`, en alles dat dieper genest is dan de serie-directory wordt genegeerd.

`ComicScanner` maakt uit het pad de `SeriesEntity` en het volume (een `BookEntity` zonder auteur)
aan en hangt het bestand eraan als `MediaFileEntity`; alle formaten van één volume (zelfde basename)
convergeren op één volume-rij. Inhoud lezen gebeurt asynchroon: `HandleComicFileFound` (disk) leest
cbz via `CbzParser` en pdf via **PDFBox** (`PdfParser`) — de paginatelling gaat op de
`MediaFileEntity`, embedded `ComicInfo.xml` (cbz) wordt volume-metadata en kan de uit de
bestandsnaam afgeleide reekspositie en titel verfijnen, en de cover (eerste cbz-pagina, of
gerenderde pdf-pagina 1) wordt naar de cache geëxtraheerd. Epub-volumes hergebruiken de volledige
`EPUB_FILE_FOUND`-pijplijn. `COMIC_SERIES_FOUND` (worker, `HandleComicSeriesFound`) voegt per taal
seriebeschrijvingen en een thumbnail toe vanuit Wikipedia/Wikidata — lokale artwork wint altijd en
wordt nooit overschreven. Pagina's worden aan de reader geserveerd door `ComicResourceController`
(`/comic/{mediaFileId}/manifest`, `/page/{index}`, `/file`).

## Podcasts (`LibraryType.PODCAST`)

Het eerste **feed-gebaseerde** librarytype: er is geen library-directory. `subscribePodcast(feedUrl)`
of de uurlijkse `PodcastRefreshScheduler` (met `lastRefreshedAt`-guard, zodat meerdere nodes niet
dubbel sweepen) stuurt `PODCAST_REFRESH_REQUESTED` (globale queue). De `RssFeedParser` van de worker
haalt de feed op met een **conditional GET** (ETag/Last-Modified), capt op 500 items, synct
kanaal-metadata + cover en maakt `PodcastEpisodeEntity`-rijen, gededupliceerd op guid.

De nieuwste N afleveringen (`app.ister.worker.podcast.auto-download-count`, default 3) krijgen
`PODCAST_EPISODE_DOWNLOAD_REQUESTED` op de **cache-directory-queue** van de node die de refresh
deed; de disk-handler downloadt de enclosure (volgt redirects) naar `{cache}/podcasts/` en stuurt
`AUDIO_FILE_FOUND`, waarna afspelen identiek is aan tracks. Oudere afleveringen downloaden
on-demand via de `downloadPodcastEpisode`-mutation. Retentie: de dagelijkse cache-cleanup verwijdert
downloads ouder dan `podcast-retention-days` (default 30), behalve als iemand middenin de aflevering
zit — de afleverings-rij blijft bestaan en kan opnieuw downloaden. Zoeken in de podcastdirectory
loopt via de gratis iTunes Search API (`ItunesSearchService`, api-module).

## NFO-bestanden

`HandleNfoFileFound` (disk) parst XML-NFO-bestanden naar metadata: titel, beschrijving en
releasedatum voor film/tv, biografie voor artiesten, review voor albums, boek-metadata voor boeken.
`PERSON_FOUND`/`ALBUM_FOUND` aan de disk-kant zoeken proactief naar `artist.nfo`/`album.nfo` naast
de media. NFO- en provider-metadata worden gededupliceerd, zodat de een nooit rijkere data van de
ander overschrijft.
