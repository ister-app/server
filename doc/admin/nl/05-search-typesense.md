# Zoeken (Typesense)

Full-text zoeken door alle libraries is optioneel en draait op
[Typesense](https://typesense.org/). Zonder werkt de server prima — de GraphQL-query `search`
geeft dan gewoon niets terug. Mét krijgen clients snel, typefouttolerant, meertalig zoeken op
titels, beschrijvingen en genres.

## Inschakelen

1. Draai een Typesense-instantie (één per cluster). `docker-compose-local.yml` toont een
   werkende servicedefinitie; in Kubernetes kan de chart hem voor je deployen.
2. Wijs de server ernaar:

   ```
   TYPESENSE_ENABLED=true
   TYPESENSE_HOST=typesense
   TYPESENSE_PORT=8108          # default
   TYPESENSE_PROTOCOL=http      # default
   TYPESENSE_API_KEY=<the key Typesense was started with>
   ```

3. Herstart de server en draai dan eenmalig de GraphQL-mutation **`reindexSearch`** om de
   initiële index op te bouwen. Tot je dat doet is bestaande media niet doorzoekbaar — alleen
   items die na het inschakelen worden aangeraakt zouden binnendruppelen.

De enabled-vlag wordt tijdens **runtime** gecontroleerd, niet in de image gebakken: dezelfde
image bedient beide modi, en zoekevents worden simpelweg geconsumeerd en weggegooid zolang de
vlag uitstaat. Je kunt `TYPESENSE_ENABLED` dus omzetten met alleen een herstart, zonder rebuild.

## Actueel blijven

Na de initiële herindexering hoef je die nooit routinematig te draaien. De index onderhoudt
zichzelf:

- nieuwe items worden geïndexeerd wanneer de scanner ze aanmaakt,
- metadataverrijking (TMDB en consorten) werkt het item bij zodra die binnenkomt,
- verwijderingen halen het item uit de index.

`reindexSearch` blijft het reparatiegereedschap: het bouwt opnieuw op in een **verse collectie
en wisselt een alias om**, zodat zoeken live blijft tijdens de rebuild. Grijp ernaar na het
inschakelen van zoeken op een bestaande database, na het terugzetten van een databaseback-up,
of als de index er ooit niet synchroon uitziet.

## Een taal toevoegen of verwijderen

Zoekvelden worden per geconfigureerde taal gegenereerd (`title_en`, `description_nl`, …) en het
collectieschema ligt **vast bij aanmaak**, dus een taalwijziging is een kleine procedure:

1. Werk `ISTER_LANGUAGES` bij (bijv. `en,nl,de`) en herstart de server(s).
2. Haal metadata opnieuw op zodat de rijen van de nieuwe taal in PostgreSQL bestaan: draai de
   mutation `analyzeLibrary` (of een re-scan) — de index kan alleen metadata tonen die in de
   database bestaat.
3. Draai eenmalig `reindexSearch`. Dat maakt een verse collectie met het nieuwe schema en
   wisselt de alias om.

Een taal verwijderen is hetzelfde minus stap 2: herindexeren laat de velden simpelweg vallen;
er wordt niets uit PostgreSQL verwijderd.

## Probleemoplossing

- **Zoeken geeft helemaal niets terug** — óf `TYPESENSE_ENABLED` staat nog op `false`, óf de
  API-key is verkeerd, óf `reindexSearch` is na het inschakelen nooit gedraaid. Het serverlog
  toont verbindingsfouten bij het opstarten en bij elke indexeerpoging.
- **Nieuwe taal niet doorzoekbaar** — je hebt stap 2 of 3 hierboven overgeslagen.
- **De index overleeft serverherstarts**, maar leeft alleen in de datamap van Typesense; raak
  je dat volume kwijt, dan bouwt één `reindexSearch` alles opnieuw op uit PostgreSQL. Hij is
  wegwerpbaar — zie [Onderhoud](06-maintenance-and-troubleshooting.md#back-up).

Hoe het indexeren intern werkt staat beschreven in de
[architectuurdocumentatie](../../architecture/nl/06-search.md).
