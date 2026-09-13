package app.ister.disk.storage;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.storage.CacheDirectoryResolver;
import app.ister.core.storage.CacheStore;
import app.ister.core.storage.LocalCacheStore;
import app.ister.core.storage.PathStrings;

import java.io.InputStream;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/** Stubs a mocked {@link CacheDirectoryResolver}'s store methods for handler tests. */
public final class CacheStoreMocks {

    private CacheStoreMocks() {
    }

    /**
     * {@code storeFor(dir)} / {@code store()} answer with a store that records nothing on disk
     * and returns {@code dir.path + "/" + key} as the stored path — for tests whose cache
     * directory is a fictional path such as {@code /cache}.
     */
    public static void fake(CacheDirectoryResolver resolver) {
        lenient().when(resolver.storeFor(any())).thenAnswer(inv -> fakeStore(inv.getArgument(0)));
        lenient().when(resolver.store()).thenAnswer(inv -> fakeStore(resolver.forThisNode()));
    }

    /** Real {@link LocalCacheStore}s, for tests whose cache directory is a temp dir. */
    public static void realLocal(CacheDirectoryResolver resolver) {
        lenient().when(resolver.storeFor(any())).thenAnswer(inv -> new LocalCacheStore(inv.getArgument(0)));
        lenient().when(resolver.store()).thenAnswer(inv -> new LocalCacheStore(resolver.forThisNode()));
    }

    public static CacheStore fakeStore(DirectoryEntity directory) {
        try {
            return doFakeStore(directory);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CacheStore doFakeStore(DirectoryEntity directory) throws java.io.IOException {
        CacheStore store = mock(CacheStore.class);
        lenient().when(store.directory()).thenReturn(directory);
        lenient().when(store.pathFor(anyString())).thenAnswer(inv -> PathStrings.join(directory.getPath(), inv.getArgument(0)));
        lenient().when(store.write(anyString(), any(Path.class), any()))
                .thenAnswer(inv -> PathStrings.join(directory.getPath(), inv.getArgument(0)));
        lenient().when(store.write(anyString(), any(InputStream.class), anyLong(), any()))
                .thenAnswer(inv -> PathStrings.join(directory.getPath(), inv.getArgument(0)));
        lenient().when(store.write(anyString(), any(byte[].class), any()))
                .thenAnswer(inv -> PathStrings.join(directory.getPath(), inv.getArgument(0)));
        return store;
    }
}
