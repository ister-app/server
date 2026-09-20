---
description: "Upload media into an Ister library from the player: admin-only, with a preview of how the scanner will recognise every file, resumable chunked transfer, and the settings a reverse proxy and a read-only media mount need."
---

# Uploading media

An administrator can add media to a library from the player instead of copying files onto the
server: a whole show, one album for an artist that is already there, or a folder full of artists.
The files land in the folder layout the scanner expects, and each one is picked up the moment it
is complete — no library scan needed.

Only users with the `admin` role can upload. The endpoints accept the normal login (bearer token)
only; the stream tokens that players use for playback are deliberately not accepted here.

## How an upload works

1. **Pick where it goes.** A library, then one of its directories (a disk, or an S3 directory). The
   player shows the free space of each and whether the server can write to it at all.
2. **Pick the folder to upload**, and where it lands:
   - *under the directory root* — a show folder, an artist folder;
   - *under an existing folder* — an album under its artist;
   - *without the picked folder itself* — a folder full of artists: its children become the
     top-level folders.
3. **Preview.** Before a single byte is sent the server tells, per file, what the scanner will make
   of it: show / season / episode, artist / album / track, author / book / chapter, series /
   volume. The preview uses the scanner's own rules, so what it shows is what you get. It also
   shows the *level* the top folder is seen at — an album dropped in the root of a music directory
   shows up as `ARTIST`, which is your cue to pick the artist folder as the parent. The folder name
   can be changed here (for instance to add the `(2019)` a show folder needs, see
   [Naming conventions](08-naming-conventions.md)).
4. **Upload.** Files go up in chunks. A dropped connection, a closed laptop or a restarted server
   costs at most one chunk: the upload continues where it stopped.

What the preview can say about a file:

| Status | Meaning |
|---|---|
| Recognised | Will be uploaded and picked up as shown |
| Ignored | The scanner would not pick it up at that place (unsupported file type, or a folder level the scan does not descend into). Not uploaded |
| Exists | The file is already there. Skipped, unless you switch on **Overwrite** for this upload |
| Busy | Another running upload is writing the same file |
| Invalid | The path cannot be stored (for example a name starting with a dot) |

### Overwriting

With **Overwrite** on, an existing file is replaced and analysed again (streams, duration,
chapters, artwork). Someone who is playing that exact file at that moment will see playback break:
its transcoded segments are discarded together with the old file.

## Requirements

### A writable media mount

Many installations mount their media read-only, which is fine for playback and scanning but not
for uploads. The player marks such a directory as not writable. Mount it read-write for the server
(the container user needs write permission on the directory root) to upload into it.

While an upload runs, its bytes are staged in a hidden `.ister-upload/` folder in the root of the
target directory — on the same filesystem, so that putting a finished 40 GB file in place is a
rename and not a second copy. Scans skip that folder. It is removed when the upload finishes, is
cancelled, or expires.

### Reverse proxy

Each chunk is one HTTP request with a 16 MB body by default. The proxy in front of Ister must
accept a request body of that size and must not buffer it to disk with a short timeout:

- nginx: `client_max_body_size 32m;` and `proxy_request_buffering off;`
- Envoy / Gateway API: no body size limit by default; check the route timeout if uplinks are slow
- a smaller chunk size (`UPLOAD_CHUNK_SIZE`, minimum 5 MB) trades throughput for friendlier
  requests

### Multi-node

A chunk is written where it arrives and is never forwarded between nodes. The player therefore
sends the upload straight to the node that serves the chosen directory (the owner of a local
directory, an attached node of an S3 directory) — the same node URL it already streams that
directory's media from. That URL must be reachable for the admin's player, see
[Multi-node](05-multi-node.md).

### S3 directories

An upload into an S3 directory is an S3 multipart upload, one part per chunk, so files larger than
the 5 GB single-request limit are no problem. The credentials of the connection need
`s3:PutObject` and `s3:AbortMultipartUpload` in addition to the read permissions.

Ister aborts the multipart uploads of cancelled and expired sessions itself. As a safety net for
the cases it cannot see (a database restore, a bucket shared with something else), set a bucket
lifecycle rule that aborts incomplete multipart uploads after a few days.

## Settings

| Environment variable | Default | Meaning |
|---|---|---|
| `UPLOAD_ENABLED` | `true` | Switch the upload endpoints off entirely |
| `UPLOAD_CHUNK_SIZE` | `16MB` | Size of one chunk request (minimum 5 MB). Very large files get larger chunks automatically, to stay under S3's 10,000-part limit |
| `UPLOAD_MAX_ACTIVE_SESSIONS` | `2` | Uploads that may run at the same time, cluster-wide |
| `UPLOAD_MAX_FILES_PER_SESSION` | `20000` | Files in one upload |
| `UPLOAD_MAX_CONCURRENT_CHUNKS` | `4` | Chunk requests one node handles at once; more are asked to retry |
| `UPLOAD_SESSION_IDLE_TIMEOUT` | `24h` | An upload that received nothing for this long is expired and its staged bytes removed |
| `UPLOAD_MIN_FREE_SPACE` | `5GB` | Space a local directory must keep free after the upload; an upload that does not fit is refused up front |
| `UPLOAD_CLEANUP_INTERVAL` | `PT1H` | How often expired uploads are swept |

## Troubleshooting

- **"Not writable" in the directory picker** — the mount is read-only for the server process.
- **Upload refused with "not enough space"** — the free space minus what other running uploads
  were promised minus `UPLOAD_MIN_FREE_SPACE` is less than the upload.
- **Chunks fail with 413** — the reverse proxy's body limit is below the chunk size.
- **A file shows as "Ignored"** — it is not at a place the scanner looks. Check the level shown for
  the top folder and the [naming conventions](08-naming-conventions.md).
- **An upload hangs at "too many uploads running"** — finish or cancel another one, or wait for the
  idle timeout to expire an abandoned one.

## Where to next

- [Libraries and media layout](04-libraries-and-media-layout.md) — the folder structure per library type
- [Naming conventions](08-naming-conventions.md) — what the scanner recognises
- [Object storage (S3)](10-object-storage.md) — S3 directories
