package app.ister.api.dto;

import app.ister.core.enums.PlaylistType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;

import java.util.UUID;

public record PlaylistInput(String name, UUID libraryId, PlaylistType type, MediaFilter filter,
                            FilterKind filterKind, SortingEnum sorting, SortingOrder sortingOrder) {
}
