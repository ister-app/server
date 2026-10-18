---
description: Volledige naamgevingsreferentie voor Ister-libraries, met de exacte map- en bestandsnaampatronen voor series, films, muziek, boeken en strips.
---

# Naamconventies per librarytype

De scanner leidt alles af uit paden en bestandsnamen; de juiste namen zijn dus wat een library
"gewoon laat werken". [Hoofdstuk 4](04-libraries-and-media-layout.md) geeft de korte versie; deze
pagina is de volledige referentie van wat de parsers werkelijk accepteren, per librarytype.
Bestanden en mappen die nergens op matchen worden **stilzwijgend genegeerd** — een titel die nooit
verschijnt is bijna altijd een naamgevingsprobleem.

Regels die overal gelden:

- Een jaartal-suffix is altijd precies **vier cijfers tussen haakjes**: `(2019)`. `[2019]`, `2019`
  en `(19)` worden niet herkend.
- Extensies zijn hoofdletterongevoelig (`Cover.JPG` werkt).
- Mappen waarvan de naam met een `.` **begint** worden volledig overgeslagen. Punten elders in een
  naam zijn prima — "J.K. Rowling", "R.E.M." en "Dr. Stone" werken allemaal.
- Het artwork-type wordt bepaald door substring-matching op de bestandsnaam (zonder extensie): een
  naam met `background` of `thumb` erin wordt de **achtergrond**, een naam met `cover`, `folder`,
  `poster` of `artist` de **poster/hoes** — en bevat een naam beide soorten tokens, dan wint de
  achtergrond. Welke van deze namen op welk mapniveau worden geaccepteerd, verschilt per
  librarytype; zie de secties hieronder.

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
  Season 04/
    s04e06-e07.mkv                # dubbele aflevering: één bestand, twee afleveringen
```

- De seriemap **moet** eindigen op `(jaar)`; een seizoensmap direct onder de library-root wordt
  genegeerd.
- Seizoensmappen heten `Season {N}` (hoofdletterongevoelig; `Season 1` en `season 01` werken
  allebei).
- Afleveringsbestanden dragen een `sNNeNN`-token (elk 1–4 cijfers, hoofdletterongevoelig):
  `s01e12.mkv`, `S05E05.mp4`. Titel en jaar van de serie komen altijd uit de seriemap, niet uit de
  bestandsnaam.
- Het **seizoensnummer in de bestandsnaam wint van de map**: `Season 02/s01e01.mkv` belandt in
  seizoen 1.
- **Multi-aflevering-bestanden**: `s04e06-e07.mkv` (ook geschreven als `s04e06e07` of
  `s04e06-07`) maakt beide afleveringen aan, wijzend naar dat ene bestand, tot maximaal **drie**
  afleveringen per bestand. Een onplausibele reeks (achterstevoren, of breder dan drie) wordt
  gelogd en behandeld als alleen de eerste aflevering.
- Videocontainers: `.mkv`, `.mp4`, `.webm`, `.m4v`, `.flv`, `.avi`. Ondertitels: `.srt` naast de aflevering, gekoppeld op
  bestandsnaam-prefix; een taalcode tussen de laatste twee punten (`s01e01.en.srt`,
  `s01e01.nld.srt`) bepaalt de ondertiteltaal. In de container ingebedde beeldondertitels
  (blu-ray-PGS, dvd-VobSub) vragen niets: de player toont ze zoals ze op de disc staan.
- NFO-bestanden: `tvshow.nfo` op serieniveau, `sNNeNN*.nfo` op afleveringsniveau.
- Artwork: `.jpg`/`.png` met `cover`, `folder`, `poster` of `artist` (poster) dan wel
  `background`/`thumb` (achtergrond) in de naam — substring-matching, achtergrond wint bij
  gemengde namen.

## Films

```
{Filmnaam} ({jaar}).mkv
```

```
Heat (1995)/
  Heat (1995).mkv
  Heat (1995)-cover.jpg
```

- De **bestandsnaam** moet eindigen op `(jaar)` vóór de extensie — dat onderscheidt een film van
  een los videobestand. Een omhullende map is optioneel; `Film (2024).mkv` direct in de
  library-root werkt ook.
- Een suffix van letters/streepjes na het jaar mag en wordt gebruikt voor artwork, volgens de
  typeregel hierboven: `Heat (1995)-cover.jpg` (of `-poster`/`-folder`) wordt de poster,
  `Heat (1995)-thumb.jpg` (of `-background`) de achtergrond. Een kaal `Heat (1995).jpg` bevat
  **geen** type-token en wordt genegeerd.
- Dezelfde containers als series (`.mkv`, `.mp4`, `.webm`, `.m4v`, `.flv`, `.avi`). `.nfo`-bestanden en ondertitels op filmniveau
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
  `01-Titel` werken allemaal; `1-01 - Titel` wordt gelezen als disc 1, track 1. Zonder
  voorloopcijfers worden de op ` - ` gescheiden segmenten afgezocht naar een nummer
  (`Artiest - 20 - Titel` → track 20), en lukt ook dat niet, dan komt het tracknummer uit de
  audiotags.
- Albummapnamen worden genormaliseerd: een formaat-suffix aan het eind (` FLAC`, `(MP3)`,
  `[FLAC]`, …) en een leidende `{Artiest} - `-prefix worden weggehaald, dus
  `The Beatles - Abbey Road (1969) [FLAC]` en `Abbey Road (1969)` zijn hetzelfde album.
- Een **plat album** direct onder de library-root (zonder artiestenmap) mag; de artiest komt dan
  uit de `album_artist`-tag in de bestanden. Zo'n map is één album, dus de `album`-tag wordt alleen
  als albumtitel gebruikt als hij de map zelf benoemt (ongeacht hoofdletters, een `(JJJJ)` aan het
  eind en de tekens die een map niet kan bevatten: `200 KM/H …` bij een map `200 KM_H …`). Een map
  met losse, ongerelateerde singles houdt dus de mapnaam en wordt niet vernoemd naar de single die
  het eerst gescand werd.
- Tags bepalen wie wat uitvoert: `album_artist` identificeert het album, `artist` de uitvoerder van
  het losse nummer — op een verzamelalbum bestaat de echte artiest alleen in die tag per track. Een
  `feat.`/`ft.`/`featuring`-gast mag in de `artist`-tag blijven staan: de server crediteert de
  primaire artiest en de gast apart, zodat het nummer op beide artiestenpagina's verschijnt. Een
  ampersand blijft ongemoeid ("Simon & Garfunkel" blijft één artiest).
- Artiestennamen worden vergeleken zonder te letten op hoofdletters en dubbele spaties, dus "ABBA"
  en "Abba" zijn één artiest.
- Audioformaten: `mp3`, `flac`, `aac`, `opus`, `ogg`, `wav`, `m4a`, `wma`.
- Speciale bestanden: `artist.nfo` op artiestenniveau plus artiestafbeeldingen met `artist`,
  `folder`, `background` of `thumb` in de naam; `album.nfo` op albumniveau plus hoezen met `cover`
  of `folder` in de naam. Andere `.nfo`-namen worden genegeerd; het type volgt de
  achtergrond-/posterregel hierboven.

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
- Audioformaten: als muziek, plus `m4b`. Speciale bestanden: `artist.nfo` op auteursniveau plus
  auteursafbeeldingen met `artist`, `folder`, `background` of `thumb` in de naam; `album.nfo` of
  `book.nfo` op boekniveau plus covers met `cover` of `folder` in de naam.

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
  worden één volume, en een `-N` aan het eind wordt weggehaald als dedupe-suffix van een
  her-download — `…part2.pdf` en `…part2-1.pdf` zijn hetzelfde volume. Een `ComicInfo.xml` wordt
  uit de **binnenkant** van het cbz-archief gelezen, niet uit de map.
- Serie-artwork: `.jpg`/`.jpeg`/`.png` met `cover`, `folder`, `poster` of `background` in de naam
  (het type volgt de achtergrond-/posterregel hierboven).

## Games

```
{platform}/{Titel ({jaar})}/…
```

```
gbc/Tetris (1989)/Tetris.gbc
gbc/Tetris (1989)/cover.jpg
psx/Crash Bandicoot (1996)/Crash Bandicoot.cue
psx/Crash Bandicoot (1996)/Crash Bandicoot.bin
ps3/Demon's Souls (2009)/PS3_GAME/PARAM.SFO
ps3/Demon's Souls (2009)/PS3_GAME/USRDIR/EBOOT.BIN
pc/Celeste (2018)/Celeste.zip
linux/Stardew Valley (2016)/stardew_valley_1.6.sh
steam/Stardew Valley (2016)/appmanifest_413150.acf
steam/Stardew Valley (2016)/Stardew Valley/Stardew Valley.exe
```

- **Platformmap eerst.** Geaccepteerde namen (hoofdletterongevoelig): `nes`, `snes`/`sfc`, `n64`,
  `gb`, `gbc`, `gba`, `nds`, `psx`/`ps1`, `ps2`, `ps3`, `gc`/`gamecube`, `wii`, `pc`/`windows`,
  `linux`, `steam`. Al het andere onder de library-root wordt genegeerd.
- **Eén game per map**, genoemd `Titel (jaar)`; het jaar is optioneel en hoort bij de identiteit
  van de game, net als bij boeken. Een los bestand direct onder de platformmap wordt genegeerd.
- **ROM-platformen**: het startbestand is het bestand met de extensie van het platform
  (`z64`/`n64`/`v64`, `gb`, `gbc`, `gba`, `nds`, `sfc`/`smc`, `nes`/`fds`, `cue`/`chd`/`pbp`,
  `iso`/`chd`, `iso`/`gcm`/`rvz`/`wbfs`); bijbehorende bestanden zoals de `.bin`-tracks van een
  `.cue` reizen mee. Submappen van een ROM-game worden niet gescand.
- **PS3**: de gamemap bevat `PS3_GAME/PARAM.SFO` (of één tussenmap dieper, `BLUS30443/PS3_GAME/…`);
  de hele boom wordt geserveerd zoals hij is. Titel en title-id komen uit `PARAM.SFO`, de cover uit
  `ICON0.PNG` als de map geen `cover.jpg` heeft. Alleen het markerbestand wordt gescand — een game
  is één item, nooit één per bestand.
- **PC**: `pc/` neemt één `.zip` van een speelklare Windows-game (het exe in de root van de zip of
  in één bovenmap); `linux/` neemt een GOG-`.sh`-installer of een `.tar`/`.tar.gz`/`.tar.xz` van een
  speelklare Linux-game.
- **Steam**: de gamemap bevat de `appmanifest_<appid>.acf` uit een Steam-bibliotheek **én** de
  installatiemap die hij noemt (`installdir`), gekopieerd uit `steamapps/common`. Beide zijn
  verplicht; de player zet ze terug in een lokale Steam-bibliotheek en start de game via Steam, dus
  het spelende Steam-account moet de game bezitten.
- Artwork: `.jpg`/`.jpeg`/`.png` met `cover`, `folder`, `poster` of `background` in de naam, direct
  in de gamemap.

## Podcasts

Geen naamregels — een `PODCAST`-library heeft helemaal geen map op schijf. Afleveringen komen uit
de RSS-feed en worden naar de cache gedownload; zie
[hoofdstuk 4](04-libraries-and-media-layout.md#podcasts).

## Als iets niet wordt opgepakt

1. Controleer het jaarformaat `(YYYY)` en, bij series/films, of het jaar op de juiste plek staat
   (map bij series, bestandsnaam bij films).
2. Controleer de diepte: afleveringen horen in `Serie (jaar)/Season N/`, comicvolumes direct in
   hun seriemap — één niveau te diep of te ondiep betekent onzichtbaar.
3. Controleer de extensie tegen de lijsten hierboven — al het andere wordt zonder foutmelding
   overgeslagen.
4. Draai na een hernoeming opnieuw `scanLibraries`; hernoemingen worden pas bij een nieuwe scan
   opgepakt.
