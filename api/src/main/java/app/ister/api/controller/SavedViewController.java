package app.ister.api.controller;

import app.ister.api.dto.SavedViewInput;
import app.ister.core.entity.SavedViewEntity;
import app.ister.core.filter.FilterJson;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.service.SavedViewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class SavedViewController {

    private final SavedViewService savedViewService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<SavedViewEntity> savedViews(@Argument UUID libraryId, @Argument FilterKind kind,
                                            Authentication authentication) {
        return savedViewService.savedViews(authentication, libraryId, kind);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public SavedViewEntity createSavedView(@Argument SavedViewInput input, Authentication authentication) {
        return savedViewService.create(authentication, input.name(), input.kind(), input.libraryId(),
                input.filter(), input.sorting(), input.sortingOrder());
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public SavedViewEntity updateSavedView(@Argument UUID id, @Argument SavedViewInput input,
                                           Authentication authentication) {
        return savedViewService.update(authentication, id, input.name(), input.kind(), input.libraryId(),
                input.filter(), input.sorting(), input.sortingOrder());
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public boolean deleteSavedView(@Argument UUID id, Authentication authentication) {
        return savedViewService.delete(authentication, id);
    }

    @SchemaMapping(typeName = "SavedView", field = "libraryId")
    public UUID libraryId(SavedViewEntity view) {
        return view.getLibraryEntity() != null ? view.getLibraryEntity().getId() : null;
    }

    /** The stored JSON, back as the typed FilterGroup tree with lists normalized to non-null. */
    @SchemaMapping(typeName = "SavedView", field = "filter")
    public MediaFilter filter(SavedViewEntity view) {
        return normalize(FilterJson.readFilter(view.getFilter()));
    }

    private MediaFilter normalize(MediaFilter filter) {
        return new MediaFilter(filter.match(), filter.conditionsOrEmpty(),
                filter.groupsOrEmpty().stream().map(this::normalize).toList(), filter.limit());
    }
}
