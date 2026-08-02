package app.ister.api.dto;

import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;

import java.util.UUID;

public record SavedViewInput(String name, FilterKind kind, UUID libraryId, MediaFilter filter,
                             SortingEnum sorting, SortingOrder sortingOrder) {
}
