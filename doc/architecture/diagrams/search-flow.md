# Search flow (Typesense)

Index updates are event-driven: entity creation and every metadata enrichment emit
`SEARCH_INDEX_REQUESTED`; a full rebuild goes through `SEARCH_REINDEX_REQUESTED` with an alias
swap so search stays live during the rebuild.

```mermaid
flowchart TD
    C["ScannerHelperService\n(getOrCreate* → *_FOUND)"] -->|"createXFoundEvent also sends"| A
    E["Enrichment handlers\n(MetadataSave, NFO, audio tags,\nMusicBrainz, TMDB cast)"] -->|after metadata save| A
    D["Track delete\n(HandleAudioFileFound.reassignTrackNumber)"] -->|action=DELETE| A

    A["SEARCH_INDEX_REQUESTED\n(entityType, entityId, action)"]
    A --> H["HandleSearchIndexRequested\n📦 search"]
    H -->|"load entity + map\nupsert/delete document"| TS[(Typesense\nalias 'media')]

    API([reindexSearch API]) --> R["SEARCH_REINDEX_REQUESTED"]
    R --> RH["HandleSearchReindexRequested\n📦 search"]
    RH -->|"new collection media_v<ts>\npage through all entities + import\nswap alias, drop old collections"| TS
```
