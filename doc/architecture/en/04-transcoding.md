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


