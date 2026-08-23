---
description: "How Ister's FFmpeg-based HLS transcoding works: lazy segments, one continuous pass per quality, background pre-transcoding and multi-node segment uploads."
---

# Transcoding

Streaming is HLS, produced by FFmpeg (via Jaffree) in the transcoder module. `HlsService` +
`HlsTranscodeService` coordinate playlist building and the FFmpeg processes. See the [transcode-flow
diagram](../diagrams/transcode-flow.md); three triggers feed the same queue: interactive playback,
the periodic pre-transcode task, and the play-queue prefetch.

## Playlists up-front, segments lazy

A `GET .../master.m3u8` cache miss sends `TRANSCODE_REQUESTED`; `HandleTranscodeRequested` →
`generateAllPlaylists` writes the master and per-stream `.m3u8` files to `tmpDir/{mediaFileId}/`,
while the HTTP thread polls until they exist. Segments are only produced when asked for: the first
`.ts` request for a quality level sends `TRANSCODE_PASS_REQUESTED`, and segment requests poll the
cache directory until the pass has written (and closed) that segment.

## One continuous pass per quality

Each quality level is **one continuous FFmpeg pass over the whole file**, not one process per
segment, using `-f segment -segment_times` so the encoder never resets PTS — that is what prevents
A/V drift. The trade-off: passes encode sequentially from t=0, so a forward seek waits for the
encoder to catch up to the requested segment.

This includes the `copy` (direct-play) qualities: video is stream-copied and cut on the same grid
the playlists advertise (see the next section for where that grid stops), and copy audio keeps any MPEG-TS-native codec (AAC, MP3,
AC-3, E-AC-3, DTS) untouched, falling back to AAC only for codecs MPEG-TS cannot carry. Copy audio
used to be advertised as a single whole-file segment generated on demand; on long files that
blocked the first request for minutes and starved the client's audio stream while video segments
raced ahead. Video passes also set `omit_video_pes_length=0`: without explicit PES lengths the
final PES packet of every segment is unbounded, and a client reading segments back to back flags
it corrupt on each boundary — a decode hiccup every few seconds.

## The grid stops where the stream stops

The cut grid comes from the video keyframes, but each stream ends somewhere else, and a cut at or
past the end of a stream produces no file at all — FFmpeg never opens that segment. A playlist that
advertised it was therefore promising something no request could ever be answered with. Two shapes
of this occur in practice: audio commonly ends before the container does (ordinary in mkv), and an
MPEG-TS stream copy of video ends at the last keyframe because it drops the final GOP.

`SegmentGrid` is therefore built per stream and per role: `HlsTranscodeService.gridFor` measures
where that stream ends — the video packet scan reports both the last keyframe (for a copy) and the
end of the last packet (for a re-encode), and audio gets one extra ffprobe that reads only the final
30 seconds through `-read_intervals`. Boundaries that would leave less than a quarter of a second
are dropped, and the last `#EXTINF` follows the measured end rather than the container duration. A
probe that cannot measure falls back to the container duration and trims nothing, so a failing probe
can never shorten a playlist. Remote inputs are never probed this way: they are read over
`/mediaFile/{id}/download`, which serves no byte ranges, so a seeking probe would degrade into
streaming the whole file across the network.

The playlist and the pass both go through `gridFor` with the same arguments, so what is advertised
and what is produced cannot drift apart. As a net for the cases the measurement cannot reach, the
segments a finished pass produced are counted before its done marker is written and the playlist is
cut back to them; that also repairs cache directories written before this existed. A segment a
completed pass never wrote answers 404, not 503 — it is not coming back, and "try again" made
clients retry for hours.

The visible consequence: for such a file the video can end a fraction of a second, and the audio a
second or so, before the container duration says. Those frames were never delivered — they were only
promised.

## Concurrency

`transcodeExecutor` is a fixed 4-thread pool, additionally bounded by the `concurrentFileSlots`
semaphore (`max-concurrent-files`, default 2). A pass holds a thread for the entire file duration.
Pre-transcoding competes for the same pool, so it is easy to starve interactive playback — which is
why background work is throttled and preemptible (below).

## Pre-transcoding and background priority

`PRE_TRANSCODE_RECENTLY_WATCHED` (per disk, every 15 minutes) reads the continue-watching entries
([chapter 5](05-continue-watching-and-status.md)) — exactly the items users will play next, plus the
episode after, so autoplay never stalls — and sends `TRANSCODE_REQUESTED` with `preTranscode=true`.
Files without analyzed streams are first sent back through `MEDIA_FILE_FOUND`.

Pre-transcode passes are narrowed by `PassFilter` from the settings of the users who pulled the file
in: only audio streams in a preferred language (`user_settings.preferred_audio_languages`, falling
back to `app.ister.languages`) and only video variants up to `max_video_height`. It never produces
the 64k audio bitrate — `HlsPlaylistBuilder` folds that group into 192k, so no master playlist ever
references it. Interactive playback uses `PassFilter.none()`: it must be able to serve any track a
player asks for, and starts passes lazily anyway. A finished background pass pulls in the next
pending pass of the same file; otherwise a budget-dropped pass would wait for the next pre-transcode
cycle.

Background passes (`background=true`) run only on spare capacity (`max-background-files`,
`max-background-passes`) and are **preempted** — FFmpeg stopped, the event discarded; the
scheduler/prefetch will resend — as soon as interactive playback needs a slot or thread. Background
FFmpeg additionally runs under OS niceness (`background-nice`, default 10, 0 = off) via a wrapper
script generated at startup, falling back to normal priority when `nice` is missing. A successful
pass writes a `done_<segmentPrefix>` marker; only that marker (not the mere presence of segments)
lets a later pre-transcode skip the pass.

## Retention

The cleanup task removes a transcode cache dir only when it has been untouched for ≥2 hours **and**
its `keep_until` deadline (the highest `keepUntilEpochMillis` ever received) has passed. Play-queue
prefetch sends +24h; the periodic pre-transcode sends +30min and refreshes it every 15 minutes as
long as the entry qualifies.

## Multi-node

Transcode queues are directory/disk-scoped (`TranscoderQueueNamingConfig` appends the directory or
disk name), so a transcode always runs on the node that owns the source file. When another node
requested it, a watcher thread uploads each stable segment to the requester via `POST
/transcode/upload/{id}/{fileName}` (`FileController`). When the source itself is remote,
`resolveInputPath` feeds FFmpeg a tokenized `…/download` URL instead of a local path.


