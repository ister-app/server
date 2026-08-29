---
description: "Hoe HLS-transcoding met FFmpeg in Ister werkt: lazy segmenten, één continue pass per kwaliteit, pre-transcoding op de achtergrond en multi-node-uploads."
---

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

Dat geldt ook voor de `copy`-kwaliteiten (direct spelen): video wordt stream-gekopieerd en geknipt
op hetzelfde raster dat de playlists adverteren (zie de volgende paragraaf voor waar dat raster
ophoudt), en copy-audio laat elke MPEG-TS-native
codec (AAC, MP3, AC-3, E-AC-3, DTS) ongemoeid en valt alleen terug op AAC voor codecs die MPEG-TS
niet kan dragen. Copy-audio werd voorheen geadverteerd als één segment dat het hele bestand
besloeg en on-demand werd gegenereerd; bij lange bestanden blokkeerde dat het eerste verzoek
minutenlang en verhongerde de audiostream van de client terwijl videosegmenten vooruit renden.
Videopasses zetten bovendien `omit_video_pes_length=0`: zonder expliciete PES-lengtes is het
laatste PES-pakket van elk segment onbegrensd, en een client die segmenten achter elkaar leest
markeert dat op elke grens als corrupt — een decodeerhapering om de paar seconden.

## Het grid stopt waar de stream stopt

Het knipraster komt uit de video-keyframes, maar elke stream eindigt ergens anders. De segment-muxer
knipt op het eerste keyframe op of na elk gevraagd tijdstip, dus een knip waar niets meer te knippen
valt levert geen bestand op — en een playlist die het tóch adverteerde beloofde iets waarop geen
enkel verzoek ooit antwoord kon krijgen. Dat gebeurt in twee vormen: audio eindigt vaak eerder dan de
container (heel gewoon in mkv), en de laatste knip van een videokopie kan precies op het allerlaatste
keyframe landen, waar het van tijdstempelafronding en de kniptolerantie afhangt of hij nog splitst.

Zo'n grens wegtrimmen kost niets. Het raster voedt de FFmpeg-pass én de playlist, dus het segment
raakt niet verweesd — het vorige loopt in plaats daarvan door tot het einde van de stream, en een
pakkettelling over een getrimd raster laat elk pakket van de bron nog aanwezig zien.

`SegmentGrid` wordt daarom per stream en per rol gebouwd: `HlsTranscodeService.gridFor` meet waar
die stream eindigt — de videopakketscan rapporteert zowel het laatste keyframe (voor een kopie) als
het einde van het laatste pakket (voor een re-encode), en audio krijgt één extra ffprobe die via
`-read_intervals` alleen de laatste 30 seconden leest. Grenzen die minder dan een kwart seconde
overlaten vervallen, en de laatste `#EXTINF` volgt het gemeten einde in plaats van de containerduur.
Een probe die niets kan meten valt terug op de containerduur en knipt niets weg, zodat een mislukte
probe een playlist nooit kan inkorten. Remote invoer wordt op deze manier nooit geprobed: die wordt
gelezen via `/mediaFile/{id}/download`, dat geen byte-ranges serveert, dus een zoekende probe zou
ontaarden in het hele bestand over het netwerk streamen.

De playlist en de pass gaan allebei via `gridFor` met dezelfde argumenten, zodat wat geadverteerd
wordt en wat geproduceerd wordt niet uit elkaar kunnen lopen. Als vangnet voor wat de meting niet
kan bereiken worden de segmenten die een afgeronde pass schreef geteld vóórdat de done-marker wordt
geschreven, en wordt de playlist daarop teruggeknipt; dat repareert meteen cachemappen van vóór deze
wijziging. Een segment dat een afgeronde pass nooit schreef antwoordt 404, geen 503 — het komt niet
meer, en "probeer opnieuw" liet clients urenlang opnieuw proberen.

Het zichtbare gevolg: bij zo'n bestand kan de playlist een fractie van een seconde, en bij audio een
seconde of zo, eerder eindigen dan de containerduur zegt. Er gaat geen beeld of geluid verloren — het
laatste segment draagt het — alleen de geadverteerde duur is nu de eerlijke.

## Concurrency

`transcodeExecutor` is een vaste pool ter grootte van
`app.ister.transcoder.hls.max-concurrent-passes` (default 4), extra begrensd door de
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

## Crop-detectie en transcoderen

De bestandsanalyse detecteert ingebakken zwarte balken en slaat de crop-rechthoek op op de
video-`MediaFileStreamEntity` ([hoofdstuk 2](02-scanning-and-analysis.md)). De transcoder past die
**nog niet** toe: geen enkele FFmpeg-pass voegt een crop-filter toe, dus streams worden
getranscodeerd mét de balken. De opgeslagen waarden worden vooralsnog alleen als stream-metadata
aan clients ontsloten.

## Retentie

Twee losse sweeps schonen de transcode-cache op, gestuurd door verschillende properties:

- **`HlsTranscodeService.cleanupOldFiles`** draait elke 15 minuten. Die verwijdert een cache-dir
  pas als elk bestand erin `app.ister.transcoder.hls.cache-retention-hours` (default 2) onaangeraakt
  is **én** de `keep_until`-deadline van de dir (de hoogste ooit ontvangen `keepUntilEpochMillis`)
  verstreken is. De playqueue-prefetch stuurt nu + `app.ister.server.prefetch.keep-hours` (default
  24 uur); de periodieke pre-transcode stuurt +30 min en ververst dat elke 15 minuten zolang de
  entry in aanmerking komt. Eén uitzondering: een dir met **uitsluitend `.m3u8`-playlists** blijft
  permanent staan — dat zijn de scan-time-playlists voor muziek, een paar KB die de eerste
  afspeelactie instant maken. De uitzondering is bewust zo smal: een dir met geëxtraheerde
  ondertitels of andere restanten maar zonder `.ts` veroudert alsnog.
- **`TmpTranscodeCleanupScheduler`** draait dagelijks (cron `app.ister.server.cache-cleanup.cron`)
  en pakt wat de eerste sweep niet kan zien: **wezen** — dirs waarvan het mediabestand niet meer in
  de database bestaat — worden onvoorwaardelijk verwijderd, en stilliggende dirs zonder actieve
  FFmpeg-pass na `app.ister.server.cache-cleanup.min-age` (default 24 uur). Deze sweep negeert
  `keep_until` en gehoorzaamt de gedeelde vlag `app.ister.server.cache-cleanup.dry-run` (default
  `true` — hij logt alleen totdat die omgezet wordt).

## Multi-node

Transcode-queues zijn directory-/disk-gescoped (`TranscoderQueueNamingConfig` plakt de directory-
of disknaam erachter), dus een transcode draait altijd op de node die het bronbestand bezit. Als een
andere node erom vroeg, uploadt een watcher-thread elk stabiel segment naar de aanvrager via `POST
/transcode/upload/{id}/{fileName}` (`FileController`). Is de bron zelf remote, dan voert
`resolveInputPath` FFmpeg een getokeniseerde `…/download`-URL in plaats van een lokaal pad.
