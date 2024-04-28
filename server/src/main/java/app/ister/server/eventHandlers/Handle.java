package app.ister.server.eventHandlers;

import app.ister.server.entitiy.ServerEventEntity;
import app.ister.server.enums.EventType;

public interface Handle {
    EventType handles();
    Boolean handle(ServerEventEntity serverEventEntity);
}
