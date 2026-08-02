package app.ister.api.dto;

import app.ister.core.enums.PlayQueueSourceType;
import app.ister.core.enums.RankKind;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;

import java.util.UUID;

public record CreatePlayQueueInput(PlayQueueSourceType sourceType, UUID sourceId, UUID startId, Boolean shuffle,
                                   RankKind rankKind, MediaFilter filter, FilterKind filterKind, UUID libraryId,
                                   SortingEnum sorting, SortingOrder sortingOrder) {
}
