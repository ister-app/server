package app.ister.server.eventHandlers;

import app.ister.server.entitiy.ServerEventEntity;

public interface Handle {
    Boolean handle(ServerEventEntity serverEventEntity);
}
