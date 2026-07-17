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
