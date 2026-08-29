---
description: "Hoe Ister gebruikers en toegang regelt: de verplichte OIDC-rol 'user', adminrollen, zichtbaarheid per library voor beperkte libraries en het delen van playback-sessies."
---

# Gebruikers, delen en toegang

Ister beheert zijn gebruikersaccounts niet zelf — dat doet de OIDC-provider ([Inleiding](00-introduction.md)).
Wat Ister *wél* beheert, is wat elke geauthenticeerde gebruiker mag zien en doen: een **admin**-vlag
afgeleid uit het token, **zichtbaarheid** per library, en het **delen van playback-sessies** per
gebruiker.

Een gebruiker wordt pas bij Ister bekend nadat hij minstens één keer heeft ingelogd — de server
leert uit het eerste token dat een gebruiker bestaat. Vanaf dat moment verschijnt hij in de lijsten
`users` en `shareableUsers`.

## De rol `user` is verplicht

Elke GraphQL- en REST-operatie is geannoteerd met `@PreAuthorize("hasRole('user')")` — een geldige
JWT alleen is niet genoeg. Maak dus bij het inrichten van je OIDC-provider **twee** rollen aan en
zorg dat beide in de `roles`-claim van het token terechtkomen:

1. een rol met de naam **`user`**, toegewezen aan *iedereen* die de server mag gebruiken, en
2. een rol met de naam **`admin`** voor beheerders (zie hieronder).

Zonder de rol `user` slaagt de login zelf, maar wordt elke query en mutation geweigerd — een
verwarrend faalbeeld, want de client lijkt ingelogd maar toont geen data. De meegeleverde
dev-realm (`keycloak/Ister-realm.json`) wijst beide rollen al toe aan zijn testgebruiker.

## Admins

Adminstatus komt volledig uit het OIDC-token, niet uit een instelling binnen Ister. De `roles`-claim
van de JWT wordt met een `ROLE_`-prefix op Spring-authorities gemapt, dus een realm- (of client-)rol
met de naam **`admin`** wordt `ROLE_admin`. De query `me` toont dit als `isAdmin`; de database houdt
een snapshot bij (`user_entity.admin`, ververst bij elk verzoek), maar het token is maatgevend. De
snapshot bestaat voor verzoeken die helemaal geen JWT meedragen: HLS-segmenten, playlists en
afbeeldingen kunnen zich authenticeren met een kortlevend stream-token
(`StreamTokenAuthenticationFilter`), en op dat pad valt de admin-bypass voor librarytoegang terug
op de snapshot op de gebruikersrij.

**Iemand admin maken:** maak in Keycloak (of je OIDC-provider) een rol `admin` aan en wijs die aan
de gebruiker toe, en zorg dat die in de `roles`-claim van het access-token terechtkomt. Een
serverherstart is niet nodig — het wordt actief bij de volgende login van die gebruiker.

Alles is beschikbaar voor elke gebruiker met de rol `user`, **behalve** deze admin-only-operaties
(afgedwongen met `@PreAuthorize("hasRole('admin')")`):

- Libraries scannen en metadata verversen — `scanLibraries`, `refreshMetadata` en de per-item
  `refreshEpisode`, `refreshMovie`, `refreshShow`, `refreshPerson`, `refreshAlbum`, `refreshTrack`
- Zoekonderhoud — `rebuildSearchIndex`
- Podcastabonnementen — `subscribePodcast`, `unsubscribePodcast`
- Beheer van librarytoegang — `setLibraryVisibleToAll`, `setUserLibraryAccess`
- De volledige `users`-lijst en het veld `User.grantedLibraries` daarop

Let op: `refreshPodcasts` (alle geabonneerde feeds opnieuw ophalen) is bewust **niet** admin-only —
elke gebruiker mag het aanroepen.

Een paar endpoints zijn met opzet niet-geauthenticeerd (`OIDCSecurityConfig`): `/actuator/**` (houd
de managementpoort intern, zie [Configuratie](03-configuration.md)) en `/time`, de klokprobe
waarmee listen-along-clients hun offset meten — die moet auth-vrij blijven zodat de
round-trip-meting zo licht mogelijk is.

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
slechts verboden. Dezelfde deny-as-not-found-regel geldt overal waar Ister toegang controleert,
inclusief het delen van playback-sessies hieronder.

## Persoonlijke data is per gebruiker

Playlists (handmatig en smart), opgeslagen views, geregistreerde devices, waarderingen en
afspeelgeschiedenis zijn eigendom van de gebruiker die ze aanmaakte: een andere gebruiker kan ze
niet lezen of wijzigen, en ook hier leest een weigering als niet gevonden. Er valt niets aan te
beheren — zie [Persoonlijke bibliotheek en devices](../../architecture/nl/09-personal-library-and-devices.md)
in de architectuurgids voor het model.

## Playback-sessies delen

Of andere gebruikers kunnen *zien* wat jij afspeelt (now-playing) en het kunnen *bedienen*
(afstandsbediening / party-mode) bepaalt de eigenaar, per account, met een optionele override per
sessie. Gebruikers stellen dit in vanuit de client; als beheerder hoef je vooral de standaardwaarden
en het model te kennen.

De voorkeuren van een gebruiker bestaan uit twee onafhankelijke scopes (`playbackSharingSettings`,
opgeslagen met `updatePlaybackSharingSettings`; bewaard door migratie V28, `user_sharing_settings`):

- **Now-playing-zichtbaarheid** — standaard **`EVERYONE`** (het oorspronkelijke gedrag: elke sessie
  is voor iedereen zichtbaar). Aan te scherpen tot `PRIVATE` of een `ALLOWLIST` van gebruikers.
- **Afstandsbediening** — standaard **`PRIVATE`** (alleen de eigenaar). Dit is een bewuste
  aanscherping van de oude party-mode waarin "elke gebruiker elke sessie bedient". Kan `EVERYONE`,
  een `ALLOWLIST` of `SAME_AS_NOW_PLAYING` zijn (hergebruikt het now-playing-publiek).

Een allowlist bevat gebruikers-id's; `shareableUsers` geeft een gewone gebruiker een lijst met
alleen namen om uit te kiezen (geen adminrechten nodig). Eén sessie kan zijn control-scope
overschrijven met `setSessionSharing` — bijvoorbeeld om één filmavond voor iedereen open te zetten
zonder de accountstandaard te wijzigen. De afdwinging volgt de deny-as-not-found-regel hierboven,
en de eigenaar slaagt altijd voor beide checks.

De interne werking — waar de scopes worden opgeslagen, hoe ze zonder databasesessie op de
now-playing-stream worden afgedwongen — staat in de architectuurgids,
[Continue watching en live status](../../architecture/nl/05-continue-watching-and-status.md#sessies-delen--privacy).
