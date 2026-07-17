# Transcode flow

Three triggers feed the same `TRANSCODE_REQUESTED` queue: the periodic pre-transcode task, an
interactive playback request, and the play-queue prefetch (which requests the next item(s) shortly
before the current one ends, in the client's reported stream settings).

```mermaid
flowchart TD
    TrigA([Periodic task]) -->|"per disk"| A
    TrigB([Playback request]) -->|direct| B
    TrigC([updatePlayQueue progress]) -->|"end approaching →\nnext item(s), keepUntil=+24h"| B

    A["PRE_TRANSCODE_RECENTLY_WATCHED\n.{diskName}"]
    A --> PA["HandlePreTranscodeRecentlyWatched\n📦 disk"]
    PA -->|"continue-watching entries per user\n(+ the episode after that)\nkeepUntil=+30min (extended every run)"| B
    PA -->|"file without analyzed\nstreams → re-analysis"| MFF["MEDIA_FILE_FOUND\n.{dirName}"]

    B["TRANSCODE_REQUESTED\n.{dirName}  preTranscode=true/false\nkeepUntilEpochMillis (optional)"]
    B --> PB["HandleTranscodeRequested\n📦 transcoder"]
    PB -->|"HLS master playlist\nHLS variant playlists\nkeep_until in the cache dir"| FS1[("HLS files\non disk")]
    PB -->|"per bitrate/pass\nbackground=true for pre-transcode"| C

    C["TRANSCODE_PASS_REQUESTED\n.{dirName}"]
    C --> PC["HandleTranscodePassRequested\n📦 transcoder"]
    PC -->|"FFmpeg via Jaffree\ngenerate HLS segments"| FS2[("HLS segments\non disk")]
```
