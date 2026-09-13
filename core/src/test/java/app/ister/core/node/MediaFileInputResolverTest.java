package app.ister.core.node;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.config.S3Properties;
import app.ister.core.entity.NodeEntity;
import app.ister.core.enums.StorageKind;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.storage.ObjectStore;
import app.ister.core.storage.ObjectStoreRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaFileInputResolverTest {

    private final NodeTokenManager tokens = mock(NodeTokenManager.class);
    private final ObjectStoreRegistry registry = mock(ObjectStoreRegistry.class);
    private final S3Properties s3Properties = new S3Properties();
    private final DirectoryRepository directoryRepository = mock(DirectoryRepository.class);
    private final MediaFileInputResolver resolver = new MediaFileInputResolver(tokens, registry, s3Properties,
            directoryRepository, "local", "http://127.0.0.1:8080/");

    private static MediaFileEntity fileOn(String nodeName, UUID id) {
        NodeEntity node = NodeEntity.builder().name(nodeName).url("http://" + nodeName + ":8080").build();
        DirectoryEntity dir = DirectoryEntity.builder().name("tv").path("/tv").nodeEntity(node).build();
        MediaFileEntity file = MediaFileEntity.builder().path("/tv/a.mkv").directoryEntity(dir).build();
        ReflectionTestUtils.setField(file, "id", id);
        return file;
    }

    @Test
    void localFileResolvesToItsPath() {
        MediaFileEntity file = fileOn("local", UUID.randomUUID());

        assertThat(resolver.isRemote(file)).isFalse();
        assertThat(resolver.resolve(file)).isEqualTo("/tv/a.mkv");
    }

    @Test
    void remoteFileResolvesToTokenizedDownloadUrlOnTheOwner() {
        UUID id = UUID.randomUUID();
        when(tokens.getDownloadToken()).thenReturn("tok");
        MediaFileEntity file = fileOn("other", id);

        assertThat(resolver.isRemote(file)).isTrue();
        assertThat(resolver.resolve(file)).isEqualTo("http://other:8080/mediaFile/" + id + "/download?token=tok");
    }

    @Test
    void subtitleDownloadUrlPointsAtTheOwner() {
        when(tokens.getDownloadToken()).thenReturn("tok");
        MediaFileStreamEntity stream = MediaFileStreamEntity.builder().build();
        UUID streamId = UUID.randomUUID();
        ReflectionTestUtils.setField(stream, "id", streamId);

        assertThat(resolver.subtitleDownloadUrl(fileOn("other", UUID.randomUUID()), stream))
                .isEqualTo("http://other:8080/mediaFileStream/" + streamId + "/download?token=tok");
    }

    @Test
    void isUrlRecognisesHttpInputs() {
        assertThat(MediaFileInputResolver.isUrl("http://x/y")).isTrue();
        assertThat(MediaFileInputResolver.isUrl("https://x/y")).isTrue();
        assertThat(MediaFileInputResolver.isUrl("/tv/a.mkv")).isFalse();
    }

    /** A gateway closing the response early must make ffmpeg resume, not end the file quietly. */
    @Test
    void urlInputsCarryReconnectOptionsLocalPathsDoNot() {
        assertThat(String.join(" ", MediaFileInputResolver.ffmpegInput("https://owner/mediaFile/x/download?token=t").buildArguments()))
                .contains("-reconnect 1").contains("-reconnect_on_network_error 1").contains("-reconnect_streamed 1");
        assertThat(String.join(" ", MediaFileInputResolver.ffmpegInput("/tv/a.mkv").buildArguments()))
                .doesNotContain("-reconnect");
    }

    private static MediaFileEntity fileOnS3(UUID id, UUID directoryId) {
        DirectoryEntity dir = DirectoryEntity.builder().name("shows-s3").path("s3://bucket/media")
                .storageKind(StorageKind.S3).s3Connection("minio").s3Bucket("bucket").s3Prefix("media").build();
        ReflectionTestUtils.setField(dir, "id", directoryId);
        MediaFileEntity file = MediaFileEntity.builder().path("s3://bucket/media/a.mkv").directoryEntity(dir).build();
        ReflectionTestUtils.setField(file, "id", id);
        return file;
    }

    /** An attached node reads S3 through its own proxy endpoint over loopback: S3 stays unreachable for ffmpeg. */
    @Test
    void s3FileOnAnAttachedNodeResolvesToTheLoopbackProxy() {
        UUID id = UUID.randomUUID();
        when(tokens.getDownloadToken()).thenReturn("tok");
        when(registry.byConnection("minio")).thenReturn(Optional.of(mock(ObjectStore.class)));
        MediaFileEntity file = fileOnS3(id, UUID.randomUUID());

        assertThat(resolver.isS3(file)).isTrue();
        assertThat(resolver.isRemote(file)).isFalse();
        assertThat(resolver.remoteNodeUrl(file)).isEmpty();
        assertThat(resolver.resolve(file)).isEqualTo("http://127.0.0.1:8080/mediaFile/" + id + "/download?token=tok");
    }

    @Test
    void s3FileResolvesToAPresignedUrlWhenFfmpegDirectIsOn() {
        UUID id = UUID.randomUUID();
        ObjectStore store = mock(ObjectStore.class);
        when(registry.byConnection("minio")).thenReturn(Optional.of(store));
        when(registry.forEntity(org.mockito.ArgumentMatchers.any())).thenReturn(store);
        when(store.presignGet("media/a.mkv", s3Properties.getPresignTtl())).thenReturn("https://minio/bucket/media/a.mkv?X-Amz-Signature=x");
        s3Properties.setFfmpegDirect(true);

        assertThat(resolver.resolve(fileOnS3(id, UUID.randomUUID()))).startsWith("https://minio/bucket/media/a.mkv?X-Amz-");
    }

    /** A node without the connection (a helper) reads through any attached node. */
    @Test
    void s3FileOnAnUnattachedNodeResolvesToAnAttachedNodesDownloadUrl() {
        UUID id = UUID.randomUUID();
        UUID directoryId = UUID.randomUUID();
        when(tokens.getDownloadToken()).thenReturn("tok");
        when(registry.byConnection("minio")).thenReturn(Optional.empty());
        NodeEntity self = NodeEntity.builder().name("local").url("http://local:8080").build();
        NodeEntity other = NodeEntity.builder().name("other").url("http://other:8080").build();
        when(directoryRepository.findAttachedNodes(directoryId)).thenReturn(List.of(self, other));
        MediaFileEntity file = fileOnS3(id, directoryId);

        assertThat(resolver.isRemote(file)).isTrue();
        assertThat(resolver.remoteNodeUrl(file)).contains("http://other:8080");
        assertThat(resolver.resolve(file)).isEqualTo("http://other:8080/mediaFile/" + id + "/download?token=tok");
    }

    @Test
    void stripTokenAlsoHidesPresignedSignatures() {
        assertThat(MediaFileInputResolver.stripToken("https://minio/b/k?X-Amz-Algorithm=AWS4&X-Amz-Signature=s"))
                .isEqualTo("https://minio/b/k");
        assertThat(MediaFileInputResolver.stripToken("http://n/mediaFile/x/download?token=t")).isEqualTo("http://n/mediaFile/x/download");
    }
}
