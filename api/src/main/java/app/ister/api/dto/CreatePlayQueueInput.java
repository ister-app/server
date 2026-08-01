package app.ister.api.dto;

import app.ister.core.enums.PlayQueueSourceType;
import app.ister.core.enums.RankKind;

import java.util.UUID;

public record CreatePlayQueueInput(PlayQueueSourceType sourceType, UUID sourceId, UUID startId, Boolean shuffle, RankKind rankKind) {
}
