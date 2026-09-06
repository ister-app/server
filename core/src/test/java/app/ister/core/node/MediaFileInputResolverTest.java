package app.ister.core.node;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileStreamEntity;
import app.ister.core.entity.NodeEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaFileInputResolverTest {

    private final NodeTokenManager tokens = mock(NodeTokenManager.class);
    private final MediaFileInputResolver resolver = new MediaFileInputResolver(tokens, "local");

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
}
