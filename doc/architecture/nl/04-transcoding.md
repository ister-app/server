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
video-`MediaFileStreamEntity` ([hoofdstuk 2](02-scanning-and-analysis.md)). De transcoder past
die **bewust niet** toe: geen enkele FFmpeg-pass voegt een crop-filter toe, en streams worden
getranscodeerd mét de balken. Croppen is het werk van de client — de player leest de rechthoek
uit de GraphQL-cropvelden, schaalt hem naar de gedecodeerde afmetingen van wat hij afspeelt
(direct play of elke HLS-variant, die verkleind kan zijn) en past hem toe als mpv `video-crop`.

Die verdeling vermijdt drie serverside problemen tegelijk: de COPY-variant (`-c:v copy`) kan
nooit gefilterd worden, dus een serverside crop zou ABR-wissels tussen COPY en een
getranscodeerde variant in kadrering laten verspringen; een serverside crop zonder bijpassend
client-contract zou dubbel worden toegepast; en de geadverteerde `RESOLUTION`-waarden in de
masterplaylist zouden per variant herrekend moeten worden. De bekende beperking van het
client-side ontwerp is de webplayer, die geen mpv heeft en de balken toont — de enige plek waar
een serverside crop ooit iets zou toevoegen.

## Bitmap-ondertitels

Ondertitels van blu-ray (PGS) en dvd (VobSub) zijn plaatjes, geen tekst. Ze worden nooit
ingebrand en nooit ge-OCR'd: de server geeft de plaatjes zelf aan de player, die ze in een
overlay boven de video tekent. Een ondertitel wisselen of wijzigen raakt dus nooit een
getranscodeerd segment, en de kijker ziet precies wat er op de disc stond.

`HlsBitmapSubtitleService` maakt per bitmapstream een cue-index plus een paar sprite-sheets in
de transcode-tmp-dir van het bestand:

- `bsub_{streamId}.json` — `{version, width, height, sheets[], cues[]}`; een cue is
  `{s, e, x, y, w, h, sheet, sx, sy, forced}`: begin/eind in milliseconden, positie en maat op
  het ondertitelcanvas van `width`×`height`, en de hoek van de sprite binnen sheet nummer `sheet`.
- `bsub_{streamId}_{NN}.png` — RGBA-sheets, in weergavevolgorde op planken gepakt (hoogte
  maximaal 2048 px), zodat een player alleen de sheet rond de afspeelpositie nodig heeft.
- `bsub_{streamId}.gen` — generatiemarker (`BITMAP_GENERATION`); verhoog hem als de parsers of
  het formaat wijzigen en verouderde artefacten worden bij het volgende verzoek opnieuw gemaakt.

Het genereren is **lazy en goedkoop**. FFmpeg decodeert niets: één run kopieert de pakketten
van *alle* bitmapstreams van het bestand eruit (`-c:s copy`, PGS als `.sup`, VobSub als `.mks`
→ `mkvextract` → `.idx`/`.sub`), en de pure-Java `PgsParser`/`VobSubParser` in
`transcoder/.../bitmapsub/` doen de rest — een paar seconden voor een blu-ray-aflevering van
40 minuten, vooral bepaald door het één keer lezen van de container. `PngEncoder` schrijft de
sheets met de hand (`Deflater` + CRC32): de PNG-writer van ImageIO heeft geen JNI-hints in de
native image, en niets hier heeft AWT nodig. Het genereren start op de achtergrond (een virtual
thread, geen transcode-slot) zodra de playlists van een bestand vooraf worden gemaakt, en de
`GET /hls/{mediaFileId}/bsub_…`-endpoints genereren bij een miss, onder een lock per bestand.
Met een gedeelde tmp-store worden de artefacten daar gepubliceerd en net als segmenten
doorgelezen. Omdat ze in de tmp-dir staan, verlopen ze samen met de rest van de
transcode-cache — geen databaserij, geen bestand in de cache-directory.

Cue-tijden zijn **ruw**: ze liggen op dezelfde bij nul beginnende tijdlijn als de positie van
de player, omdat de pakketkopie net als de transcode-passes naar de start van de container
herbaseert. De `SUBTITLE_OFFSET_MS`-verschuiving van de WebVTT/SRT-renditions compenseert een
detail van de MPEG-TS-muxer en geldt hier niet. Voor `dvb_subtitle` bestaat geen parser; die
wordt niet geserveerd.

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

Transcode-queues zijn directory-gescoped (`TranscoderQueueNamingConfig` boven op
`DirectoryQueueNames`, [hoofdstuk 1](01-event-system.md)), dus een transcode draait op de node die
het bronbestand bezit — of op een helper-node die de directory onder `app.ister.helper.disks` met de
`TRANSCODE`-job opsomt en dezelfde queues meeleest. Als de node die transcodeert niet de node is die
het afspelen bedient, uploadt een watcher-thread elk stabiel segment naar de eigenaar via `POST
/transcode/upload/{id}/{fileName}` (`FileController`). Is de bron zelf remote, dan voert
`MediaFileInputResolver` FFmpeg de getokeniseerde `/mediaFile/{id}/download`-URL van de eigenaar in
plaats van een lokaal pad; dat endpoint serveert byte-ranges, zodat FFmpeg's seeks en de
stream-eind-probes van de transcoder goedkoop blijven. Ook het pad van een
`EXTERNAL_SUBTITLE`-stream is eigenaar-lokaal (cache-directory of een sidecar-`.srt`), dus een
remote transcoder haalt hem eenmalig op via `/mediaFileStream/{id}/download` naar de
transcode-cachemap van het bestand.

Een S3-directory heeft geen eigenaar: elke gekoppelde node mag de transcode-queues lezen, en de
node waar de client mee praat is niet noodzakelijk de node die de pass draait.
`TranscodeRequestedData` en `TranscodePassRequestedData` dragen daarom `requestingNodeUrl`; een
transcoderende node pusht naar die node als het een andere is (`HlsService.uploadTarget`), wat de
segment-push van helper naar eigenaar hergebruikt.

Met `app.ister.server.tmp-s3-connection` gezet wordt het push-doel in plaats daarvan de
cluster-gedeelde `TmpStore` (`S3TmpStore`, `tmp/{mediaFileId}/…`): de watcher publiceert stabiele
segmenten, playlists en — als laatste, na de pass — de done-markering. De leeskant is een
read-through: elke `HlsService`-lookup kijkt eerst in de lokale tmp-map en haalt daarna de
ontbrekende playlist, markering of het segment uit de opslag daarnaartoe (`syncFromShared`), zodat
de invarianten aan de encoderkant (groottestabiliteit, done-markeringen, `keep_until`, de lokale
sweeps) onaangeroerd blijven en `HlsController` echte bestanden blijft serveren. Wachten op een
elders geproduceerd segment pollt de opslag in plaats van de lokale pass (`waitForSegment`). De
tmp-opschoning veegt ook de opslag, één node per run onder een advisory lock; heranalyse
(`deleteHlsCache`) verwijdert de gepubliceerde map ook.
