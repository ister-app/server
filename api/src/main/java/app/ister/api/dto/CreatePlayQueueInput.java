package app.ister.api.dto;

import app.ister.core.enums.PlayQueueSourceType;

import java.util.UUID;

public record CreatePlayQueueInput(PlayQueueSourceType sourceType, UUID sourceId, UUID startId, Boolean shuffle) {
}
