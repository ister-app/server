package app.ister.disk.scanner;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.storage.ObjectRef;
import app.ister.core.storage.ObjectStat;
import app.ister.core.storage.ObjectStore;
import lombok.extern.slf4j.Slf4j;

/**
 * The S3 counterpart of {@link AnalyzerSimpleFileVisitor}: a delimiter-based recursive listing
 * of the directory's prefix, pruned per "sub-directory" (common prefix) by the same
 * {@link DirectoryPruner} rules, feeding every object to the shared {@link ScanEntryDispatcher}.
 * Listing one level at a time costs one request per directory — the same shape as a filesystem
 * walk — and lets the pruner skip whole subtrees without listing them.
 */
@Slf4j
class S3LibraryScanner {

    private final DirectoryEntity directoryEntity;
    private final ObjectStore store;
    private final ScanEntryDispatcher dispatcher;

    S3LibraryScanner(DirectoryEntity directoryEntity, ObjectStore store, ScanEntryDispatcher dispatcher) {
        this.directoryEntity = directoryEntity;
        this.store = store;
        this.dispatcher = dispatcher;
    }

    void scan() {
        ObjectRef root = ObjectRef.parse(directoryEntity.getPath());
        if (!root.bucket().equals(store.bucket())) {
            throw new IllegalStateException("Directory " + directoryEntity.getName() + " is on bucket "
                    + root.bucket() + " but connection " + directoryEntity.getS3Connection() + " serves " + store.bucket());
        }
        walk(root.key().isEmpty() ? "" : root.key() + "/");
    }

    private void walk(String prefix) {
        ObjectStore.Listing listing = store.listShallow(prefix);
        for (ObjectStat object : listing.objects()) {
            String uri = store.uri(object.key());
            dispatcher.dispatch(new ScanEntry(uri, true, object.size(), object.lastModified()));
        }
        for (String child : listing.childPrefixes()) {
            String childUri = store.uri(child);
            if (DirectoryPruner.shouldDescend(directoryEntity, childUri)) {
                walk(child + "/");
            } else {
                log.trace("Skipping {}", childUri);
            }
        }
    }
}
