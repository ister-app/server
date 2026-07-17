# Naamconventies per librarytype

De scanner leidt alles af uit paden en bestandsnamen; de juiste namen zijn dus wat een library
"gewoon laat werken". [Hoofdstuk 3](03-libraries-and-media-layout.md) geeft de korte versie; deze
pagina is de volledige referentie van wat de parsers werkelijk accepteren, per librarytype.
Bestanden en mappen die nergens op matchen worden **stilzwijgend genegeerd** — een titel die nooit
verschijnt is bijna altijd een naamgevingsprobleem.

Regels die overal gelden:

- Een jaartal-suffix is altijd precies **vier cijfers tussen haakjes**: `(2019)`. `[2019]`, `2019`
  en `(19)` worden niet herkend.
- Extensies zijn hoofdletterongevoelig (`Cover.JPG` werkt).
- Mappen waarvan de naam met een `.` begint worden volledig overgeslagen.
- Zet geen punt in een mapnaam — een naam met een punt wordt voor een bestand aangezien.

## Series

```
{Serienaam} ({jaar})/Season {N}/s{NN}e{NN}.mkv
```

```
The Wire (2002)/
  tvshow.nfo
  cover.png
  Season 01/
    s01e12.mkv
    s01e12.en.srt
    s01e12-thumb.jpg
```

- De seriemap **moet** eindigen op `(jaar)`; een seizoensmap direct onder de library-root wordt
  genegeerd.
- Seizoensmappen heten `Season {N}` (hoofdletterongevoelig; `Season 1` en `season 01` werken
  allebei).
- Afleveringsbestanden dragen een `sNNeNN`-token (elk 1–4 cijfers, hoofdletterongevoelig):
  `s01e12.mkv`, `S05E05.mp4`. Titel en jaar van de serie komen altijd uit de seriemap, niet uit de
  bestandsnaam.
- Videocontainers: `.mkv`, `.mp4`. Ondertitels: `.srt` naast de aflevering, gekoppeld op
  bestandsnaam-prefix; een taalcode tussen de laatste twee punten (`s01e01.en.srt`,
  `s01e01.nld.srt`) bepaalt de ondertiteltaal.
- NFO-bestanden: `tvshow.nfo` op serieniveau, `sNNeNN*.nfo` op afleveringsniveau.
- Artwork: `.jpg`/`.png` met `cover` (poster) of `background`/`thumb` (achtergrond) in de naam.

## Films

```
{Filmnaam} ({jaar}).mkv
```

```
Heat (1995)/
  Heat (1995).mkv
  Heat (1995).jpg
```

- De **bestandsnaam** moet eindigen op `(jaar)` vóór de extensie — dat onderscheidt een film van
  een los videobestand. Een omhullende map is optioneel; `Film (2024).mkv` direct in de
  library-root werkt ook.
- Een suffix van letters/streepjes na het jaar mag en wordt gebruikt voor artwork:
  `Heat (1995)-thumb.jpg` wordt de achtergrond.
- Dezelfde containers als series (`.mkv`, `.mp4`). `.nfo`-bestanden en ondertitels op filmniveau
  worden op dit moment niet opgepakt.

## Muziek

```
{Artiest}/{Album ({jaar})}/{NN} - {Tracktitel}.flac
```

```
The Beatles/
  artist.nfo
  artist.jpg
  Abbey Road (1969)/
    album.nfo
    cover.jpg
    01 - Come Together.flac
Grease_ Soundtrack (1991)/        # plat: geen artiestenmap
  01-Grease.flac
```

- Een optioneel `(jaar)` op de artiestenmap is het geboortejaar van de artiest; op de albummap is
  het het releasejaar.
- Tracknummers komen uit de voorloopcijfers van de bestandsnaam: `01 - Titel`, `01. Titel`,
  `01-Titel` werken allemaal; `1-01 - Titel` wordt gelezen als disc 1, track 1. Zonder bruikbaar
  nummer komt het tracknummer uit de audiotags.
- Een **plat album** direct onder de library-root (zonder artiestenmap) mag; de artiest komt dan
  uit de `album_artist`-tag in de bestanden.
- Audioformaten: `mp3`, `flac`, `aac`, `opus`, `ogg`, `wav`, `m4a`, `wma`.
- Speciale bestanden: `artist.nfo` en artiestafbeeldingen (`artist.jpg`, `folder.jpg`) op
  artiestenniveau; `album.nfo` en hoezen (`cover.jpg`, `folder.png`) op albumniveau. Andere
  `.nfo`-namen worden genegeerd.

## Boeken

```
{Auteur}/{Boeknaam}.epub
{Auteur}/{Boeknaam ({jaar})}/{NNN}_{Hoofdstuk}.mp3
```

```
Terry Pratchett/
  artist.nfo
  Guards! Guards!.epub            # de epub…
  Guards! Guards!/                # …en het audioboek: zelfde boek
    album.nfo
    cover.jpg
    001_Hoofdstuk 1.mp3
    002_Hoofdstuk 2.mp3
```

- De epub onder de auteur en de audioboekmap **komen samen op één boek** wanneer ze dezelfde
  boeknaam dragen. Een `(jaar)` op de auteursmap is het geboortejaar, op het boek het
  publicatiejaar.
- Hoofdstukbestanden beginnen met 1–4 cijfers (`001_`, `01 - `, `12.`); het nummer bepaalt alleen
  de volgorde en mag bij 0 beginnen. Een epub mag ook ín de boekmap staan
  (`Auteur/Boek/Boek.epub`).
- Een voorlees-editie mag `{Boeknaam} (karaoke).epub` heten zodat hij op hetzelfde boek landt —
  maar of hij echt voorleesaudio heeft (EPUB 3 media overlays) wordt uit de **inhoud** van de epub
  gedetecteerd, nooit uit de naam. Ook het ISBN wordt uit de epub zelf gelezen, niet uit de
  bestandsnaam.
- Audioformaten: als muziek, plus `m4b`. Speciale bestanden: `artist.nfo` op auteursniveau;
  `album.nfo` of `book.nfo` plus covers op boekniveau.

## Comics

```
{Serienaam ({startjaar})}/{volumebestand}
```

```
Rick and Morty (2023)/
  cover.jpg
  Volume 27.cbz
  Vol 3 - Ondertitel.pdf
  Issue 8.epub
Attack on Titan (2009)/
  attackontitan_vol27.pdf
```

- Precies **twee niveaus**: seriemap met daarin de volumebestanden. Diepere nesting en losse
  bestanden in de library-root worden genegeerd. Het `(jaar)` op de seriemap is het **startjaar**
  van de serie.
- Het volumenummer wordt uit de bestandsnaam geparsed, in volgorde van voorkeur: `vol`/`volume` +
  nummer (`Volume 27`, `vol 1.5`, `attackontitan_vol27`), `issue` + nummer of `#N` (`Issue 8`,
  `Saga #12`), of losse cijfers aan het eind (`fairytail 3`). Bestanden zonder nummer sorteren
  achteraan.
- Formaten: `.cbz`, `.pdf`, `.epub`. Meerdere formaten van hetzelfde volume (zelfde basisnaam)
  worden één volume. Een `ComicInfo.xml` wordt uit de **binnenkant** van het cbz-archief gelezen,
  niet uit de map.
- Serie-artwork: `.jpg`/`.jpeg`/`.png` met `cover`, `folder`, `poster` of `background` in de naam.

## Podcasts

Geen naamregels — een `PODCAST`-library heeft helemaal geen map op schijf. Afleveringen komen uit
de RSS-feed en worden naar de cache gedownload; zie
[hoofdstuk 3](03-libraries-and-media-layout.md#podcasts).

## Als iets niet wordt opgepakt

1. Controleer het jaarformaat `(YYYY)` en, bij series/films, of het jaar op de juiste plek staat
   (map bij series, bestandsnaam bij films).
2. Controleer de diepte: afleveringen horen in `Serie (jaar)/Season N/`, comicvolumes direct in
   hun seriemap — één niveau te diep of te ondiep betekent onzichtbaar.
3. Controleer de extensie tegen de lijsten hierboven — al het andere wordt zonder foutmelding
   overgeslagen.
4. Draai na een hernoeming opnieuw `scanLibrary`; hernoemingen worden pas bij een nieuwe scan
   opgepakt.
