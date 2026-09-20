# Upload flow

An admin upload writes files into a library directory and hands each finished file to the normal
scan pipeline. The preview and the scan share one decision (`Scanners.analyzableBy` +
`DirectoryPruner`), the bytes never pass through RabbitMQ, and the chunks go straight to the node
that can write the directory.

```mermaid
flowchart TD
    P([Player, admin]) -->|"GET /library-upload/directories"| ANY["any node"]
    P -->|"POST /preview\n(paths + sizes only)"| PV["UploadPreviewService\nreal scanners + DirectoryPruner\n+ exists / busy check"]
    P -->|"POST /sessions\n→ servingNode.url"| SS["UploadSessionService\n(preview re-run, space check)"]
    SS --> DB[("upload_session\nupload_file\nupload_part")]

    P -->|"POST …/chunk?offset=\nraw body, Content-Length"| CH["chunk()\nshort tx · stream · short tx"]
    CH -->|LOCAL| L["LocalLibraryWriteStore\n&lt;root&gt;/.ister-upload/&lt;session&gt;/&lt;file&gt;.part"]
    CH -->|S3| S3["S3LibraryWriteStore\nmultipart upload, part = chunk"]
    CH --> DB

    P -->|"POST …/complete"| CO["complete()"]
    CO -->|"atomic move /\ncompleteMultipartUpload"| T[("target path in the library")]
    CO --> PUB["UploadedFilePublisher"]
    PUB -->|"new file"| FSR["FILE_SCAN_REQUESTED\n→ scan flow"]
    PUB -->|"replaced file:\ndrop HLS tmp + local copy"| RE["MEDIA_FILE_FOUND / AUDIO_FILE_FOUND /\nEPUB_FILE_FOUND / COMIC_FILE_FOUND / IMAGE_FOUND"]

    CL([UploadCleanupScheduler\nhourly, every node]) -->|"idle sessions → EXPIRED\nabort parts, remove staging"| DB
```
