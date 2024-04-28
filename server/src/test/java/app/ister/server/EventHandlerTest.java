package app.ister.server;

import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.EventType;
import app.ister.server.eventHandlers.Handle;
import app.ister.server.repository.ServerEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventHandlerTest {
    @Mock
    ServerEventRepository serverEventRepositoryMock;
    @Mock
    Handle handleMock;

    @Test
    void happyFlow() {
        ServerEventEntity serverEventEntity = ServerEventEntity.builder()
                        .eventType(EventType.SHOW_FOUND)
                                .build();

        when(handleMock.handles()).thenReturn(EventType.SHOW_FOUND);
        when(serverEventRepositoryMock.findAllByFailedIsFalse(any(Pageable.class))).thenReturn(List.of(serverEventEntity));
        when(handleMock.handle(serverEventEntity)).thenReturn(true);

        EventHandler subject = new EventHandler(serverEventRepositoryMock, handleMock);

        subject.handleEvents();

        verify(serverEventRepositoryMock).delete(serverEventEntity);
    }

    @Test
    void setFailedWhenHandleFailed() {
        ServerEventEntity serverEventEntity = ServerEventEntity.builder()
                .eventType(EventType.SHOW_FOUND)
                .build();

        when(handleMock.handles()).thenReturn(EventType.SHOW_FOUND);
        when(serverEventRepositoryMock.findAllByFailedIsFalse(any(Pageable.class))).thenReturn(List.of(serverEventEntity));
        when(handleMock.handle(serverEventEntity)).thenReturn(false);

        EventHandler subject = new EventHandler(serverEventRepositoryMock, handleMock);

        subject.handleEvents();

        verify(serverEventRepositoryMock).save(serverEventEntity);
    }

    @Test
    void noHandlerFound() {
        ServerEventEntity serverEventEntity = ServerEventEntity.builder()
                .eventType(EventType.EPISODE_FOUND)
                .build();

        when(handleMock.handles()).thenReturn(EventType.SHOW_FOUND);
        when(serverEventRepositoryMock.findAllByFailedIsFalse(any(Pageable.class))).thenReturn(List.of(serverEventEntity));

        EventHandler subject = new EventHandler(serverEventRepositoryMock, handleMock);

        subject.handleEvents();

        verify(serverEventRepositoryMock, never()).save(serverEventEntity);
    }
}