# Transcoding

Streaming is HLS, geproduceerd door FFmpeg (via Jaffree) in de transcoder-module. `HlsService` +
`HlsTranscodeService` coördineren het bouwen van playlists en de FFmpeg-processen. Zie het
[transcode-flow-diagram](../diagrams/transcode-flow.md); drie triggers voeden dezelfde queue:
interactieve playback, de periodieke pre-transcode-taak en de playqueue-prefetch.

## Playlists vooraf, segmenten lazy

Een cache-miss op `GET .../master.m3u8` stuurt `TRANSCODE_REQUESTED`; `HandleTranscodeRequested` →
`generateAllPlaylists` schrijft de master- en per-stream-`.m3u8`-bestanden naar
`tmpDir/{mediaFileId}/`, terwijl de HTTP-thread pollt tot ze bestaan. Segmenten worden pas
geproduceerd als erom gevraagd wordt: het eerste `.ts`-verzoek voor een kwaliteitsniveau stuurt
`TRANSCODE_PASS_REQUESTED`, en segmentverzoeken pollen de cache-directory tot de pass dat segment
geschreven (en gesloten) heeft.

## Eén continue pass per kwaliteit

Elk kwaliteitsniveau is **één continue FFmpeg-pass over het hele bestand**, geen proces per segment,
met `-f segment -segment_times` zodat de encoder de PTS nooit reset — dát voorkomt A/V-drift. De
keerzijde: passes encoderen sequentieel vanaf t=0, dus een sprong vooruit wacht tot de encoder het
gevraagde segment heeft ingehaald.

## Concurrency

`transcodeExecutor` is een vaste pool van 4 threads, extra begrensd door de
`concurrentFileSlots`-semafoor (`max-concurrent-files`, default 2). Een pass houdt een thread vast
voor de volledige duur van het bestand. Pre-transcoding concurreert om dezelfde pool, dus het is
makkelijk om interactieve playback uit te hongeren — vandaar dat achtergrondwerk gethrottled en
preëmptabel is (zie hieronder).

## Pre-transcoding en achtergrondprioriteit

`PRE_TRANSCODE_RECENTLY_WATCHED` (per disk, elke 15 minuten) leest de continue-watching-entries
([hoofdstuk 5](05-continue-watching-and-status.md)) — precies de items die gebruikers hierna gaan
spelen, plus de episode dáárna, zodat autoplay nooit stilvalt — en stuurt `TRANSCODE_REQUESTED` met
`preTranscode=true`. Bestanden zonder geanalyseerde streams gaan eerst terug door
`MEDIA_FILE_FOUND`.

Pre-transcode-passes worden versmald door `PassFilter`, op basis van de instellingen van de
gebruikers die het bestand binnentrokken: alleen audiostreams in een voorkeurstaal
(`user_settings.preferred_audio_languages`, met fallback op `app.ister.languages`) en alleen
videovarianten tot `max_video_height`. De 64k-audiobitrate wordt nooit geproduceerd —
`HlsPlaylistBuilder` vouwt die groep samen met 192k, dus geen enkele master-playlist verwijst
ernaar. Interactieve playback gebruikt `PassFilter.none()`: die moet elke track kunnen serveren
waar een speler om vraagt, en start passes toch al lazy. Een afgeronde achtergrondpass trekt de
volgende wachtende pass van hetzelfde bestand binnen; anders zou een wegens budget gedropte pass
moeten wachten op de volgende pre-transcode-cyclus.

Achtergrondpasses (`background=true`) draaien alleen op restcapaciteit (`max-background-files`,
`max-background-passes`) en worden **gepreëmpt** — FFmpeg gestopt, het event vervalt; de
scheduler/prefetch stuurt later opnieuw — zodra interactieve playback een slot of thread nodig
heeft. Achtergrond-FFmpeg draait daarnaast met OS-niceness (`background-nice`, default 10, 0 = uit)
via een bij startup gegenereerd wrapper-script, met terugval op normale prioriteit als `nice`
ontbreekt. Een succesvolle pass schrijft een `done_<segmentPrefix>`-marker; alleen die marker (niet
de enkele aanwezigheid van segmenten) laat een latere pre-transcode de pass overslaan.

## Retentie

De cleanup-taak verwijdert een transcode-cache-dir pas als die ≥2 uur onaangeraakt is **én** de
`keep_until`-deadline (de hoogste ooit ontvangen `keepUntilEpochMillis`) verstreken is. De
playqueue-prefetch stuurt +24 uur; de periodieke pre-transcode stuurt +30 min en ververst dat elke
15 minuten zolang de entry in aanmerking komt.

## Multi-node

Transcode-queues zijn directory-/disk-gescoped (`TranscoderQueueNamingConfig` plakt de directory-
of disknaam erachter), dus een transcode draait altijd op de node die het bronbestand bezit. Als een
andere node erom vroeg, uploadt een watcher-thread elk stabiel segment naar de aanvrager via `POST
/transcode/upload/{id}/{fileName}` (`FileController`). Is de bron zelf remote, dan voert
`resolveInputPath` FFmpeg een getokeniseerde `…/download`-URL in plaats van een lokaal pad.
