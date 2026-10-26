---
description: "Metadatapijplijnen per mediatype in Ister: TMDB voor films en series, MusicBrainz voor muziek, Open Library en Wikidata voor boeken, plus strips en podcasts."
---

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

De identiteit van een film komt uit het pad (`Titel (jaar)`), dus dezelfde film onder twee
maptitels ("De Smurfen" naast "The Smurfs") wordt als twee rijen gescand. Zodra TMDB de tweede aan
een id koppelt dat een andere film van de library al draagt, geeft `MovieFoundHandle` hem aan
`MovieMergeService`: de bestanden, afspeelwachtrij- en afspeellijst-items verhuizen naar de
verrijkte rij, kijkstatus en beoordelingen verhuizen tenzij de gebruiker er daar al een heeft, en
de eigen metadata, afbeeldingen en credits van de dubbele vervallen. `ScannerHelperService.getOrCreateMovie`
herleidt een samengevoegde titel via de verhuisde bestanden, zodat een herscan hem niet opnieuw
aanmaakt. Twee kopieën in dezelfde scanronde kunnen er nog doorheen glippen als beide handlers
draaien voordat een van beide het TMDB-id zet; de eerstvolgende `refreshMetadata` voegt ze samen.

**Matching** (`TmdbSearchService` + `TmdbResultSelector`): het jaar in een mapnaam zit er vaak één of
twee naast en het jaarfilter van TMDB is strikt, dus een verkeerd jaar levert niets of ongerelateerde
titels op. De zoektocht wordt daarom stap voor stap verbreed en stopt bij de eerste treffer: zoeken
mét jaar en een exacte (genormaliseerde) titelmatch nemen; hetzelfde in elke andere geconfigureerde
taal, zodat een Nederlandse mapnaam de Nederlandse TMDB-titel treft; zoeken zónder jaar en een exacte
titel nemen die binnen twee jaar van het mapjaar is uitgebracht; en pas daarna terugvallen op de
populariteitsvolgorde van TMDB binnen de jaargefilterde resultaten, beperkt tot titels die minstens
de helft van de woorden uit de zoekterm delen. Een titel die zo nergens op past blijft zonder
metadata in plaats van verkeerde te krijgen; hernoem de map en draai `refreshMetadata`.

TMDB kent geen "onvertaald"-signaal: gevraagd om een taal waarin het geen vertaling heeft, geeft
het de originele titel terug (Japans voor de meeste anime) met een lege overview.
`TmdbLanguageFallback` herkent dat (gelokaliseerde titel gelijk aan de originele terwijl de
originele taal noch de gevraagde noch de terugvaltaal `en` is), waarna de show-/filmhandlers de
Engelse details ophalen en de titel daarvan, plus overview en tagline waar de gelokaliseerde leeg
zijn, in de metadata-rij van die taal zetten. De rij houdt de gevraagde taal, dus speler en
zoekindex hoeven niets bijzonders te doen.

Uit de details-response wordt meer gehaald dan titel/overview. **Per taal** krijgt de metadata-rij
ook de gelokaliseerde `genre`-lijst (kommagescheiden namen — dit activeert het GENRE-filter en de
`genre_<tag>`-zoekvelden voor video) en de `tagline`. **Taalonafhankelijke** feiten zijn kolommen op
de entiteit zelf, één keer gezet bij de eerste geslaagde taal: `tmdbId`, `imdbId`,
`voteAverage`/`voteCount` (null zolang er nul stemmen zijn), `runtime` (films/afleveringen, in
minuten), `status`, `homepage`, `originCountry`, `studios`, collectie-id/-naam (films, uit
`belongs_to_collection`) en `networks` (shows). Na de talenlus doet `TmdbExtrasService` per item nog
een paar extra endpoint-aanroepen: release dates/content ratings (keuring voor
`app.ister.worker.tmdb.certification-country`, standaard `US`, met terugval naar US en daarna
willekeurig), videos (één YouTube-trailerkey, met voorkeur voor officiële trailers), keywords en
TV-external-ids (imdb). Elke extras-aanroep is afzonderlijk fout-tolerant: een fout wordt gelogd en
laat het veld null in plaats van het event te dead-letteren (de chart-e2e stubt TMDB met WireMock).

Credits komen in dezelfde pass mee: movie credits, show aggregate credits en episode credits (cast
+ guest stars) worden `PersonEntity`- + `CreditEntity`-rijen, direct in de database geschreven. Een
`CreditEntity` koppelt een persoon aan precies één van movie/show/episode. Een `PersonEntity` wordt
gedeeld tussen acteurs en muziekartiesten; TMDB-castleden worden gededupliceerd tegen bestaande
personen op **exacte naam + geboortejaar**. Het GraphQL-type `Credit` ontsluit de terugverwijzingen
(`movie`/`show`/`episode`, batch-resolved in `CreditController`), zodat een filmografie opvraagbaar
is via `personById { credits { movie/show/episode } }`.

### Gerelateerde series

`Show.related(limit)` (`ShowController`, `ShowRepository.findRelatedShowIds`) maakt van diezelfde
verrijking een "meer zoals dit"-lijst, zonder ook maar één extra provider-aanroep: het scoort de
andere shows **uit dezelfde bibliotheek** op gedeelde TMDB-keywords (3,0 per stuk, afgetopt op vijf
zodat één lange keywordlijst niet alles overheerst), gedeelde genres (1,5 per stuk) en gedeelde
castleden (1,0 per stuk, afgetopt op drie). Een overlappend netwerk, land van herkomst of een
releasejaar binnen vijf jaar levert elk een halve punt op, maar rangschikt alleen shows die al
inhoudelijk overlappen — een gedeeld uitzendjaar is op zichzelf geen relatie. Shows die niets delen
vallen af, dus een show zonder TMDB-verrijking geeft een lege lijst in plaats van willekeurige buren,
en de lijst wordt beter met elke `refreshMetadata`.

Keywords, networks en origin countries zijn de met komma-spatie samengevoegde strings op
`show_entity`; genres staan per taal op de metadata-rijen, dus de query koppelt de metadata-rijen van
beide shows **op taal** (de genrenamen zijn immers vertaald) en neemt de best passende taal. De
kandidaten zitten per definitie in de bibliotheek waartoe de aanroeper via `showById` al toegang
kreeg, dus het veld heeft geen eigen toegangscontrole nodig. Het is bewust géén `@BatchMapping`:
alleen de detailpagina van een show vraagt het op, voor één show tegelijk.

## Muziek (MusicBrainz)

Artiest-directories worden `PersonEntity`-rijen (`PERSON_FOUND`), albums `AlbumEntity`
(`ALBUM_FOUND`), tracks lopen via `AUDIO_FILE_FOUND` (ffprobe + ID3-tags + embedded cover).
Album-identiteit komt uit het **pad**, nooit uit tags; de tag-titel van het album belandt in `MetadataEntity`, en een rij die uit een bestand kwam dat het album inmiddels verlaten heeft wordt bij de volgende scan vervangen in plaats van voor altijd te blijven staan. De artiest van een track komt juist wél uit
de `artist`-**tag** (met de pad-artiest als fallback): op verzamelalbums bestaat de uitvoerende per
track alleen daar, terwijl het album de pad-afgeleide albumartiest houdt. De worker-`HandleAlbumFound` bevraagt
MusicBrainz en downloadt de release-group-cover; de disk-kant
(`HandlePersonFound`/`HandleAlbumFound`) zoekt naar `artist.nfo`/`album.nfo`. Artiesten krijgen een
`birthYear` (MusicBrainz life-span, of de mapnaam) — precies zodat de TMDB-acteur-dedup hierboven ze
kan matchen.

Elke track draagt daarnaast **credits** (`TrackCreditEntity`): de primaire artiest — dezelfde persoon
als `TrackEntity.personEntity` — plus eventuele featured gasten, één rij per persoon. Die koppeltabel
maakt de verzamelalbum- en gastoptredens van een artiest bereikbaar vanaf haar pagina
([hoofdstuk 7](07-api-and-auth.md)); het album houdt één albumartiest.

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
  game nooit kan koppelen. De positie/oorspronkelijk-jaar-verrijking voor boeken die al een reeks
  hebben (`findBookInSeries`) past dezelfde P50-check toe: het reekslabel alleen kan de film niet
  afwijzen, want een filmreeks kan exact hetzelfde label dragen als de boekenreeks ("Harry Potter"). Labelmatching accepteert naast de geconfigureerde talen ook Wikidata's
  `mul`-label (standaard voor alle talen): bots consolideren labels die in alle talen gelijk zijn
  daarin en verwijderen de per-taal-labels. Ontdekking dekt wat de andere twee niet zien: titels zonder scheider
  ("Harry Potter en de steen der wijzen") en audiobook-only boeken zonder epub-metadata. Wanneer
  epub-metadata een reeks *aanmaakt*, wordt `BOOK_FOUND` eenmalig opnieuw afgevuurd voor de
  reeksloze boeken van de auteur, zodat ontdekking binnen één scan convergeert ongeacht de
  scanvolgorde. De analyze-backfill dispatcht `BOOK_FOUND` bovendien opnieuw voor elk reeksloos
  boek waarvan de auteur een reeks heeft — een herscan slaat al bekende bestanden over, dus zonder
  dit zou een boek waarvan de ontdekking te vroeg draaide (of destijds tegen Wikidata faalde) voor
  altijd buiten zijn reeks blijven.
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

## Games (`LibraryType.GAME`, migratie V53)

Games zijn het eerste librarytype waarvan de inhoud **niet als stream wordt geserveerd**: de
desktop-player downloadt een game, start een lokale emulator (RetroArch met de libretro-core die
`GamePlatform` noemt, RPCS3) of de game zelf / Steam, en synchroniseert de saves van de gebruiker
terug. De server is de bibliotheek plus de save-cloud per gebruiker.

De identiteit van `game_entity` is `(library, platform, name, pathYear)`; `form` (`GameForm`) zegt
wat de bestanden zijn: `ROM`, `PS3_FOLDER`, `PC_PORTABLE_ZIP`, `PC_LINUX_INSTALLER`,
`PC_LINUX_PORTABLE`, `STEAM`. Eén-bestands-vormen koppelen `MediaFileEntity.gameEntity`-rijen
(`gameEntryFile = false` voor de `.bin`-companions van een `.cue`); mapvormen hebben geen rij per
bestand (zie [hoofdstuk 2](02-scanning-and-analysis.md#games)). `title` is een weergave-override
(`PARAM.SFO` `TITLE`, de Steam-naam, later een metadataprovider), `name` blijft de pad-identiteit.

`HandleGameFound` (worker, `GAME_FOUND`) verrijkt: voor PS3 leest hij `PARAM.SFO`
(`ParamSfoParser`: `TITLE`, `TITLE_ID`) en kopieert `ICON0.PNG` als cover naar de cache-store als
de map geen artwork heeft; voor pc- en Steam-vormen lost hij de **save-locaties** op uit het
community-[Ludusavi-manifest](https://github.com/mtkennerly/ludusavi-manifest) —
`LudusaviManifestService` downloadt de ~10 MB YAML hooguit eens per `refresh-hours`, bewaart hem in
de cache-store onder `ludusavi/manifest.yaml` en reduceert hem tot twee in-memory indexen (Steam-
app-id → entry, genormaliseerde titel → entry); de matching is strikt (app-id, anders exacte
genormaliseerde titel — een verkeerde map is erger dan geen synchronisatie). Het resultaat komt in
`manifest_save_paths` (JSON, `GameSavePaths`: padsjablonen met Ludusavi-placeholders zoals
`<winAppData>`, plus een `os`), `manifest_matched_by` en `steam_cloud`; de `save_paths`-override van
een beheerder wint altijd. `GameMetadataProvider` is de seam voor een externe bron: `SteamGameMetadataProvider` geeft een
Steam-game zonder artwork een cover en achtergrond van Steams publieke CDN (op app-id,
`steam://`-sourceUri; achievement-iconen tellen niet als artwork); IGDB kan later volgen.

**Manifest en bestanden** (`GameResourceController`, disk): `GET /game/{id}/manifest` somt elk
bestand op (pad, grootte, `lastModified`, entry-vlag) plus een `version` en `updatedAt` (de nieuwste
bestandstijd). `version` is wat een geïnstalleerde client vergelijkt om te zien dat de game op de
server veranderd is, en het is dezelfde waarde als `Game.version` in GraphQL: `GameVersion`
(database-module) geeft de Steam-`buildId` uit de `.acf` als die er is, anders de eerste 16
hex-tekens van SHA-256 over `sizeBytes|fileCount|dateUpdated` — één regel op basis van alleen de
entity, omdat de bestandslijst van een mapgame alleen in de disk-module wordt gelopen. De
manifest-ETag is `"<gameId>-<version>"` (`If-None-Match` → 304). Gamebestanden
(`/game/{id}/file/{*relative}`) zijn **niet** immutable — een her-upload behoudt het pad — dus ze
antwoorden met `Cache-Control: private, no-cache`, een ETag `"<grootte>-<lastModifiedMillis>"` en
`Last-Modified` (304 bij een passende `If-None-Match`), naast de Range-ondersteuning. Alleen de
save-zips hieronder zijn immutable.

**Saves** (`GameSaveService`, disk): één zip per speelsessie, raw geüpload naar
`POST /game/{id}/saves?baseVersion=N`; versies tellen op per (gebruiker, game), de nieuwste
`app.ister.games.save-retention` blijven bewaard, de objecten staan in de cache-store onder
`saves/<userId>/<gameId>/<version>.zip` en worden gerefereerd door `game_save_entity` (zodat de
cache-opruiming ze laat staan). Een head die nieuwer is dan de `baseVersion` van de client geeft
**409** met de gegevens van de head — een ander apparaat speelde tussendoor — tenzij `force=true`;
`…/saves/{v}/restore` kopieert een oudere versie als nieuwe head zodat de historie lineair blijft.
De upload is "korte tx → streamen naar tmp → korte tx", zoals de chunks van de admin-upload.

**Prestaties** komen van de client, die ze na een sessie uit de lokale emulator-/storebestanden
leest (RPCS3's `TROPCONF.SFM` + `TROPUSR.DAT`, Steams `appcache/stats`-KeyValues):
`POST /game/{id}/achievements` bevat de volledige definitielijst plus de ontgrendelingen
(`GameAchievementService`: upsert per `(game, source, externalId)`, een ontgrendeltijd gaat nooit
terug), iconen komen als PNG-body binnen en worden als `ImageType.ICON`-afbeeldingen opgeslagen.

**Speelstatus** is één `WatchStatusEntity` per gebruiker per game (`playTimeMs`, `dateUpdated` =
laatst gespeeld), geschreven door de mutatie `recordGamePlayed`, die ook de continue-watching-entry
bijhoudt (type `GAME`, de game is zijn eigen groep).

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

## Door een beheerder gekozen artwork (migratie V54)

Scans en providers bepalen de artwork van een item automatisch: één `COVER` en één `BACKGROUND`
per taal van TMDB (`posterPath`/`backdropPath`), de release-group-hoes van MusicBrainz, een
Wikipedia-portret, een `folder.jpg` naast de bestanden, of — als een film of aflevering niets anders
heeft — een frame dat ffmpeg uit het midden van de video pakt (`HandleMediaFileFound`). Clients
kiezen zelf tussen die rijen (eerst een lokaal bestand, dan de laagste id;
`ImageUtil.getImageByType` in de player, gespiegeld door `PlayQueueController.artworkOf`). Een
beheerder kan dat overrulen:

- **`ImageEntity.selected`** markeert dé afbeelding voor item en type (hoogstens één per
  item+type). Clients en de server-side keuzes proberen eerst een geselecteerde afbeelding. De vlag
  staat op de rij: de keuze *is* een rij, er is geen aparte override-tabel.
- **Een geforceerde refresh behoudt haar.** `AnalyzeDataHandle` verwijdert de afbeeldingen van een
  item behalve de geselecteerde voordat het `*_FOUND` opnieuw uitstuurt;
  `HandleAlbumFound`/`HandlePersonFound` zien "alleen nog een geselecteerde afbeelding" als "nog
  geen afbeelding", zodat de automatische hoes naast de keuze toch opnieuw wordt opgehaald.
- **Waar nieuwe afbeeldingen vandaan komen** (`ArtworkAdminService`, disk-module; GraphQL in
  `ImageAdminController`, [hoofdstuk 7](07-api-and-auth.md#beheerders-artwork)):
  - een opgeslagen afbeelding (`selectImage`), bv. het gegenereerde videoframe boven de TMDB-still;
  - een provider-alternatief (`imageCandidates.remote` → `selectRemoteImage`): `TmdbArtworkProvider`
    (worker, een `RemoteArtworkProvider`) somt TMDB's volledige poster-/backdrop-/still-/profielsets
    op voor films, shows, afleveringen en personen, met dezelfde `TMDB://<originele url>`-sourceUri
    als de scan, zodat een kandidaat die de server al heeft wordt herkend en zonder download
    geselecteerd. Alleen TMDB's eigen beeldhost wordt gedownload: de sourceUri is beheerdersinvoer;
  - een frame uit de video van het item op een positie (`selectVideoFrame`): dezelfde ffmpeg-aanroep
    als de automatische still, via `MediaFileInputResolver`, dus ook op een node die het bestand niet
    heeft. De positie is de tijdlijn van het mediabestand zelf;
  - een upload (`POST /artwork/upload`, jpeg/png/webp).

  Deze rijen worden direct aangemaakt — niet via `IMAGE_FOUND`, de beheerder wacht — onder
  `artwork/<uuid>.<ext>` in de cache-store van de node, met de blur-hash inline berekend (de sweep
  draait alleen bij een metadata-refresh). Uploads en frames krijgen `sourceUri`
  `upload://<user>/<uuid>` of `frame://<mediaFileId>@<ms>` en dus `MetadataSource.USER` (geen
  bronvermelding); alleen die kunnen weer verwijderd worden (`deleteImage`). `clearSelectedImage`
  geeft het slot terug aan de automatische regel.

## NFO-bestanden

`HandleNfoFileFound` (disk) parst XML-NFO-bestanden naar metadata: titel, beschrijving en
releasedatum voor film/tv, biografie voor artiesten, review voor albums, boek-metadata voor boeken.
`PERSON_FOUND`/`ALBUM_FOUND` aan de disk-kant zoeken proactief naar `artist.nfo`/`album.nfo` naast
de media. NFO- en provider-metadata worden gededupliceerd, zodat de een nooit rijkere data van de
ander overschrijft.
