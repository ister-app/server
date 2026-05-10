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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NodeTokenManagerTest {

    @InjectMocks
    private NodeTokenManager subject;

    @Mock
    private StreamTokenService streamTokenService;

    @Test
    void initStoresTokenFromService() {
        UUID tokenId = UUID.randomUUID();
        StreamTokenEntity tokenEntity = StreamTokenEntity.builder().token(tokenId).build();
        when(streamTokenService.createNodeToken()).thenReturn(tokenEntity);

        subject.init();

        assertEquals(tokenId.toString(), subject.getToken());
    }

    @Test
    void refreshUpdatesTokenFromService() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        StreamTokenEntity t1 = StreamTokenEntity.builder().token(first).build();
        StreamTokenEntity t2 = StreamTokenEntity.builder().token(second).build();
        when(streamTokenService.createNodeToken()).thenReturn(t1).thenReturn(t2);

        subject.init();
        subject.refresh();

        assertEquals(second.toString(), subject.getToken());
    }
}
