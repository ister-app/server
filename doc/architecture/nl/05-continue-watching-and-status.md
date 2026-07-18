# Continue watching en live status

Twee onafhankelijke mechanismen met één ding gemeen: geen van beide wordt bij het lezen afgeleid.
De continue-watching-lijst is een voorberekende tabel; live status is een fanout van in-memory
registries.

## Continue watching (`recentlyWatched`)

Zie het [continue-watching-flow-diagram](../diagrams/continue-watching-flow.md). De
`continue_watching`-tabel (migratie V20) bevat één rij per user per container — show / film / boek
/ podcastaflevering (`group_id`) — die wijst naar het item om mee te hervatten.
`ContinueWatchingService` (database-module) is de eigenaar; de GraphQL-query `recentlyWatched` is
één geïndexeerde read.

- **Incrementeel, zelfde transactie.** `onWatchStatusChanged(watchStatus)` wordt aangeroepen
  *binnen de transactie* van elke watch-status-write (`PlayQueueService.updateWatchStatus`,
  `BookController.updateReadingProgress`, `ReadingProgressController`), zodat cache en waarheid
  samen committen — geen event ertussen. **Elk nieuw codepad dat een `WatchStatusEntity` schrijft
  moet dit aanroepen**, anders loopt de lijst achter tot de nachtelijke rebuild.
- **Overdracht bij uitkijken.** Een onafgemaakt item hervat zichzelf; een uitgekeken item draagt
  over aan de volgende ongekeken episode/chapter, gevonden met één geïndexeerde query
  (`EpisodeRepository.findNextUnwatchedEpisodeId`, `ChapterRepository.findNextUnfinishedChapterId`)
  — nooit door een hele show te laden.
- **Rijen met alleen NULL-targets blijven staan.** Als er niets meer te vervolgen valt, gaan alle
  target-kolommen op NULL, maar de rij blijft bewust bestaan. Voegt de scanner later een episode
  toe, dan maakt `recomputeForShow` (aangeroepen vanuit `ScannerHelperService.getOrCreateEpisode`;
  `recomputeForBook` voor chapters) die nieuwe episode de target en verschijnt de show weer in de
  lijst. De rij verwijderen zou die terugkeer onmogelijk maken.
- **Self-healing.** `ContinueWatchingRebuildScheduler` (worker) queuet nachtelijk (03:30) per user
  een `CONTINUE_WATCHING_REBUILD_REQUESTED`, en eenmalig bij startup zolang de tabel leeg is (de
  backfill na V20). `rebuildForUser` gooit de rijen van de user weg en herberekent ze uit
  `watch_status_entity`, wat ook entries opruimt waarvan de media verdwenen is.
- **Race-veilige upsert.** Writes lopen via een native `INSERT … ON CONFLICT DO UPDATE`
  (`ContinueWatchingRepository.upsert`), zodat twee gelijktijdige heartbeats van één user niet
  kunnen falen op een unique-constraint-race; `last_watched` beweegt via `GREATEST` alleen vooruit.
- `PreTranscodeService` leest dezelfde tabel — de entries *zijn* de "wat gaan ze hierna spelen"-set
  ([hoofdstuk 4](04-transcoding.md)) — in plaats van zelf de kijkgeschiedenis af te lopen.

## Live status (`core/.../status/`)

Los van de werkqueues publiceert elke node zijn toestand naar een **fanout-exchange**
(`StatusExchangeConfig`) die elke node op zijn eigen anonieme queue consumeert
(`StatusEventListener`), zodat de clusterstatus overal convergeert en elke node een subscription kan
beantwoorden.

| Producer | Publiceert |
| --- | --- |
| `NodeActivityPublisher` | node-heartbeat |
| `QueueDepthPoller` | RabbitMQ-queuedieptes |
| `ProcessingActivityAdvice` | AOP-advice dat meldt welke handler op dat moment bezig is |
| `RecentFailuresBuffer` | recente handler-mislukkingen (gevoed vanuit het dead-letter-pad) |
| `PlaybackStatusService` | playback-heartbeats van clients → `PlaybackSessionRegistry`, verlopen via `PlaybackSessionSweeper` |

`ServerStatusBroadcaster` verbindt de registries met de GraphQL-websocket-subscriptions:
`serverActivity` en `nowPlaying` (`ServerStatusController`) en `playbackCommands(playQueueId)`
(`PlaybackCommandController` — party-mode-afstandsbediening:
PLAY/PAUSE/NEXT/SEEK/SKIP_TO_ITEM/QUEUE_CHANGED).

Twee invarianten voordat je aan deze code komt:

- De activity- en now-playing-sinks zijn **replay-latest**: een nieuwe subscriber moet meteen de
  huidige toestand krijgen, en een emit vanaf een RabbitMQ-listener-thread mag nooit blokkeren.
- De command-sink is bewust **best-effort en non-replaying**: een re-subscriber die het laatste
  commando opnieuw afgespeeld krijgt, zou het opnieuw uitvoeren (bijvoorbeeld nogmaals seeken).

Handlers hier doen **geen database-toegang** — RabbitMQ-listener-threads hebben geen
Hibernate-sessie ([hoofdstuk 1](01-event-system.md)); alles wat ze aanraken is in-memory
registry-state.

### Sessies delen & privacy

Zichtbaarheid van now-playing en afstandsbediening staan onder controle van de eigenaar
(`PlaybackSharingService`, gemodelleerd op `LibraryAccessService`: een per-eigenaar-config die ~15s
gecachet wordt en door de sharing-mutaties wordt geïnvalideerd). Twee scopes, opgeslagen in
`user_sharing_settings` met per-capability-allowlists in `user_sharing_grant` (VIEW / CONTROL):

- **Now-playing** staat standaard op `EVERYONE` (behoudt het oorspronkelijke gedrag waarbij alle
  sessies zichtbaar waren); instelbaar op `PRIVATE` of een `ALLOWLIST` van gebruikers.
- **Afstandsbediening** staat standaard op `PRIVATE` (alleen de eigenaar) — een bewuste aanscherping
  van de oude party-modus waarin elke gebruiker elke sessie kon bedienen. Kan `EVERYONE`, een
  `ALLOWLIST` of `SAME_AS_NOW_PLAYING` zijn. Ook **per sessie** te overrulen (`setSessionSharing`
  schrijft `play_queue_entity.control_scope_override` plus de eigen `play_queue_control_grant`-lijst
  van die sessie).

Handhavingspunten, allemaal **deny-as-not-found** (nooit een 403):

- `ServerStatusController.nowPlaying`/`serverActivitySnapshot` filteren de sessielijst per kijker met
  `canView` en stempelen elke overgebleven sessie met een per-kijker-`controllable`-vlag
  (`canControl`). De now-playing-sink emit nog steeds op de RabbitMQ-listener-thread, dus de
  subscription-resolver stapt over naar `Schedulers.boundedElastic()` vóór de (gecachete)
  sharing-lookups — de listener-thread blijft DB-vrij. De per-sessie-override + allowlist reizen mee
  in `PlaybackStatusData`, ingebed op de heartbeat-request-thread (die wél een Hibernate-sessie heeft),
  zodat de resolver de queue nooit opnieuw hoeft te lezen.
- `PlaybackCommandController.sendPlaybackCommand` en
  `PlayQueueService.getPlayQueue`/`getEditableQueue` toetsen op `canControl`; een geweigerde aanroeper
  krijgt een genegeerd commando / lege Optional. De eigenaar passeert altijd beide controles.

`shareableUsers` levert een niet-admin, alleen-naam-gebruikerslijst zodat een gewone gebruiker een
allowlist kan vullen (de `users`-query blijft admin-only).
