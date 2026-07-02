package app.ister.transcoder;

import app.ister.core.entity.StreamTokenEntity;
import app.ister.core.service.StreamTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeTokenManagerTest {

    @InjectMocks
    private NodeTokenManager subject;

    @Mock
    private StreamTokenService streamTokenService;

    private static StreamTokenEntity entity(UUID token) {
        return StreamTokenEntity.builder().token(token).build();
    }

    @Test
    void initStoresSeparateDownloadAndUploadTokens() {
        UUID download = UUID.randomUUID();
        UUID upload = UUID.randomUUID();
        when(streamTokenService.createNodeDownloadToken()).thenReturn(entity(download));
        when(streamTokenService.createNodeUploadToken()).thenReturn(entity(upload));

        subject.init();

        assertEquals(download.toString(), subject.getDownloadToken());
        assertEquals(upload.toString(), subject.getUploadToken());
    }

    @Test
    void refreshUpdatesTokensFromService() {
        UUID firstDownload = UUID.randomUUID();
        UUID secondDownload = UUID.randomUUID();
        UUID firstUpload = UUID.randomUUID();
        UUID secondUpload = UUID.randomUUID();
        when(streamTokenService.createNodeDownloadToken())
                .thenReturn(entity(firstDownload)).thenReturn(entity(secondDownload));
        when(streamTokenService.createNodeUploadToken())
                .thenReturn(entity(firstUpload)).thenReturn(entity(secondUpload));

        subject.init();
        subject.refresh();

        assertEquals(secondDownload.toString(), subject.getDownloadToken());
        assertEquals(secondUpload.toString(), subject.getUploadToken());
    }
}
