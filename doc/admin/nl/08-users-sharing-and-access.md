# Gebruikers, delen en toegang

Ister beheert zijn gebruikersaccounts niet zelf — dat doet de OIDC-provider ([Inleiding](00-introduction.md)).
Wat Ister *wél* beheert, is wat elke geauthenticeerde gebruiker mag zien en doen: een **admin**-vlag
afgeleid uit het token, **zichtbaarheid** per library, en het **delen van playback-sessies** per
gebruiker.

Een gebruiker wordt pas bij Ister bekend nadat hij minstens één keer heeft ingelogd — de server
leert uit het eerste token dat een gebruiker bestaat. Vanaf dat moment verschijnt hij in de lijsten
`users` en `shareableUsers`.

## Admins

Adminstatus komt volledig uit het OIDC-token, niet uit een instelling binnen Ister. De `roles`-claim
van de JWT wordt met een `ROLE_`-prefix op Spring-authorities gemapt, dus een realm- (of client-)rol
met de naam **`admin`** wordt `ROLE_admin`. De query `me` toont dit als `isAdmin`; de database houdt
een snapshot bij (`user_entity.is_admin`, ververst bij elk verzoek), maar het token is
maatgevend.

**Iemand admin maken:** maak in Keycloak (of je OIDC-provider) een rol `admin` aan en wijs die aan
de gebruiker toe, en zorg dat die in de `roles`-claim van het access-token terechtkomt. Een
serverherstart is niet nodig — het wordt actief bij de volgende login van die gebruiker.

Alles is beschikbaar voor elke geauthenticeerde gebruiker, **behalve** deze admin-only-operaties
(afgedwongen met `@PreAuthorize("hasRole('admin')")`):

- Libraries scannen en analyseren — `scanLibrary`, `analyzeLibrary` en de `analyzeData…`-mutations
- Zoekonderhoud — `reindexSearch`
- Podcastabonnementen — `subscribePodcast`, `unsubscribePodcast`
- Beheer van librarytoegang — `setLibraryVisibleToAll`, `setUserLibraryAccess`
- De volledige `users`-lijst

## Zichtbaarheid van libraries

Elke library is óf **zichtbaar voor iedereen** óf **beperkt**:

- Nieuwe en bestaande libraries staan standaard op **zichtbaar voor iedereen**
  (`library_entity.visible_to_all`, migratie V27), zodat installaties van vóór deze functie
  ongewijzigd blijven werken.
- `setLibraryVisibleToAll(libraryId, visibleToAll)` (admin) schakelt een library tussen de twee
  toestanden.
- Voor een beperkte library verleent of ontneemt `setUserLibraryAccess(userId, libraryId, granted)`
  (admin) één gebruiker toegang. Het is idempotent — twee keer verlenen, of een niet-bestaande
  toekenning intrekken, geeft geen probleem.

**Admins zien altijd elke library**, beperkt of niet. Een niet-admin ziet de voor-iedereen-zichtbare
libraries plus de beperkte libraries die expliciet aan hem zijn toegekend. Toegang wordt overal
afgedwongen waar media wordt geserveerd (`LibraryAccessService`, `MediaAccessEnforcementFilter`), en
een weigering leest als **niet gevonden**, nooit als 403 — een beperkte library is onzichtbaar, niet
slechts verboden.

## Playback-sessies delen

Of andere gebruikers kunnen *zien* wat jij afspeelt (now-playing) en het kunnen *bedienen*
(afstandsbediening / party-mode) bepaalt de eigenaar, per account, met een optionele override per
sessie. Gebruikers stellen dit in vanuit de client; als beheerder hoef je vooral de standaardwaarden
en het model te kennen.

De voorkeuren van een gebruiker bestaan uit twee onafhankelijke scopes (`playbackSharingSettings`,
opgeslagen met `updatePlaybackSharingSettings`):

- **Now-playing-zichtbaarheid** — standaard **`EVERYONE`** (het oorspronkelijke gedrag: elke sessie
  is voor iedereen zichtbaar). Aan te scherpen tot `PRIVATE` of een `ALLOWLIST` van gebruikers.
- **Afstandsbediening** — standaard **`PRIVATE`** (alleen de eigenaar). Dit is een bewuste
  aanscherping van de oude party-mode waarin "elke gebruiker elke sessie bedient". Kan `EVERYONE`,
  een `ALLOWLIST` of `SAME_AS_NOW_PLAYING` zijn (hergebruikt het now-playing-publiek).

Een allowlist bevat gebruikers-id's; `shareableUsers` geeft een gewone gebruiker een lijst met
alleen namen om uit te kiezen (geen adminrechten nodig). Eén sessie kan zijn control-scope
overschrijven met `setSessionSharing` — bijvoorbeeld om één filmavond voor iedereen open te zetten
zonder de accountstandaard te wijzigen. De afdwinging is opnieuw deny-as-not-found, en de eigenaar
slaagt altijd voor beide checks.

De interne werking — waar de scopes worden opgeslagen, hoe ze zonder databasesessie op de
now-playing-stream worden afgedwongen — staat in de architectuurgids,
[Continue watching en live status](../../architecture/nl/05-continue-watching-and-status.md#sessies-delen--privacy).
