---
description: Zo definieer je libraries en directories in Ister en zet je films, series, muziek, boeken, strips en podcasts op schijf zodat de scanner ze herkent.
---

# Libraries en media-indeling

Een **library** is een benoemde verzameling van één type (`MOVIE`, `SHOW`, `MUSIC`, `BOOK`,
`COMIC` of `PODCAST`). Een **directory** is een pad op schijf dat aan een library hangt; een
library kan meerdere directories beslaan, zelfs over nodes heen. De scanner bepaalt wat een
bestand is aan de hand van zijn **pad**, dus de indeling op schijf doet ertoe.

## Libraries en directories configureren

Geïndexeerde properties, uit `disk/src/main/resources/disk.properties` (als env vars:
`APP_ISTER_DISK_LIBRARIES_0_NAME` enzovoort):

```properties
app.ister.disk.libraries[0].name=shows
app.ister.disk.libraries[0].type=SHOW
app.ister.disk.libraries[1].name=books
app.ister.disk.libraries[1].type=BOOK

app.ister.disk.directories[0].name=disk1
app.ister.disk.directories[0].path=/disk1
app.ister.disk.directories[0].library=shows
app.ister.disk.directories[1].name=disk2
app.ister.disk.directories[1].path=/disk2
app.ister.disk.directories[1].library=shows
```

Directorynamen **moeten uniek zijn over het hele cluster** — ze benoemen de werkqueues per
directory ([Multi-node](05-multi-node.md)). Schrijf paden **zonder slash aan het eind** en houd
ze stabiel: het pad wordt letterlijk in de database opgeslagen en als stringprefix vergeleken,
dus `/disk1` later veranderen in `/disk1/` telt als een padwijziging. Rijen worden bij elke
start uit deze configuratie aangemaakt of bijgewerkt.

## Verwachte indeling per type

Dit is de korte versie; [hoofdstuk 7](08-naming-conventions.md) is de volledige naamreferentie
(exacte patronen, geaccepteerde extensies, speciale bestanden en veelgemaakte fouten).

**Series** — `Show Name (year)/Season NN/sNNeNN.mkv`:

```
The Wire (2002)/Season 01/s01e01.mkv
```

**Films** — één bestand (of een map) per film, naam eindigend op het jaar:

```
Heat (1995)/Heat (1995).mkv
```

**Muziek** — `Artist/Album/track`:

```
Miles Davis/Kind of Blue/01 So What.flac
```

**Boeken** — één logisch boek per auteur, in twee uitwisselbare vormen die samenkomen in
hetzelfde boek: een epub direct onder de auteur, en/of een luisterboekmap met genummerde
hoofdstukken:

```
Terry Pratchett/Guards! Guards!.epub
Terry Pratchett/Guards! Guards!/001_Chapter 1.mp3
```

Voorlees-epubs (EPUB 3 media-overlay) worden automatisch herkend aan de **inhoud** van de epub,
nooit aan de bestandsnaam.

**Comics** — serie-eerst: `{Series Name (optional year)}/Volume 27.cbz`. Ook `.pdf` en
`.epub`; losse patronen als `attackontitan_vol27.pdf`, `series_issue8.pdf` en `name#3.cbz`
worden getolereerd.

Herkende videocontainers: `mkv`, `mp4`; ondertitels: `.srt` naast de video (beeldondertitels in
mkv worden geëxtraheerd en met OCR omgezet); lokale artwork: `jpg`/`png`; `.nfo`-bestanden
worden gelezen voor metadata-hints.

## Podcasts

Een `PODCAST`-library heeft **helemaal geen directory** nodig — hij is feed-gebaseerd:

- Abonneer vanuit de client (of via de GraphQL-mutation `subscribePodcast(feedUrl)`); het
  zoeken in de client gebruikt de gratis iTunes Search API.
- Feeds verversen **elk uur**; de nieuwste afleveringen (standaard 3, `auto-download-count`)
  worden automatisch naar de cachemap gedownload, oudere op verzoek wanneer een gebruiker ze
  afspeelt.
- Downloads verlopen na 30 dagen (`podcast-retention-days`), tenzij iemand middenin een
  aflevering zit.

## Scannen en analyseren

De onderhouds-mutations (ook beschikbaar in de beheerschermen van de client):

| Actie | Wanneer | Kosten |
| --- | --- | --- |
| `scanLibraries(libraryId?)` | Er zijn nieuwe bestanden toegevoegd — er is geen filesystem-watcher. Optioneel per bibliotheek. | Goedkoop; bekende bestanden worden overgeslagen. |
| `refreshMetadata(MISSING, libraryId?)` | Backfill: haal metadata/afbeeldingen alleen op waar ze ontbreken (bv. na het toevoegen van een TMDB-key), bereken ontbrekende blur-hashes. | Goedkoop en idempotent; altijd veilig. |
| `refreshMetadata(FORCE, libraryId)` | Eén bibliotheek opnieuw opbouwen: opgeslagen metadata, afbeeldingen en streaminfo wissen en alles opnieuw ophalen (bv. na een verkeerde match, of om nieuwe velden op oude items te vullen). | Zwaar: een externe fetch per item, ffprobe per bestand. |
| `refreshMovie/Show/Episode/Person/Album/Track(id)` | Dezelfde wipe-en-herfetch voor één item (het ⋮-menu op de detailpagina). | Eén item (een show waaiert uit naar zijn afleveringen). |
| `rebuildSearchIndex` | De Typesense-index opnieuw opbouwen in een verse collectie (na het inschakelen van zoeken of het wijzigen van talen). | Leest de hele database één keer; zoeken blijft beschikbaar. |

Alle zijn asynchroon — ze zetten events in de queue en keren meteen terug; de voortgang is
zichtbaar in het activiteitenscherm van de client. Een scan haalt **geen** metadata opnieuw op voor
bestaande items, en een MISSING-verversing raakt items die al compleet zijn niet aan. De details van de pipeline staan in de
[architectuurdocumentatie](../../architecture/nl/02-scanning-and-analysis.md).

## Verder lezen

- [Multi-node](05-multi-node.md) — directories verspreid over meerdere servers
- [Onderhoud](07-maintenance-and-troubleshooting.md) — wat er in de loop van de tijd met caches gebeurt
