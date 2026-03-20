package app.ister.api.controller;

import app.ister.core.entity.*;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class MovieController {
    private final MovieRepository movieRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final LibraryRepository libraryRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<MovieEntity> movies(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId) {
        String sortingString = sorting.orElse(SortingEnum.DATE_CREATED).getDatabaseString();
        Sort sortBy;
        if (sortingOrder.isPresent()) {
            sortBy = sortingOrder.get() == SortingOrder.ASCENDING
                    ? Sort.by(sortingString).ascending()
                    : Sort.by(sortingString).descending();
        } else {
            sortBy = Sort.by(sortingString).descending();
        }
        Pageable pageable = PageRequest.of(page.orElse(0), size.orElse(10), sortBy);
        return libraryId.flatMap(libraryRepository::findById)
                .map(lib -> movieRepository.findByLibraryEntity(lib, pageable))
                .orElseGet(() -> movieRepository.findAll(pageable));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<MovieEntity> movieById(@Argument UUID id) {
        return movieRepository.findById(id);
    }

    @SchemaMapping(typeName = "Movie", field = "metadata")
    public List<MetadataEntity> metadata(MovieEntity movieEntity) {
        return movieEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Movie", field = "images")
    public List<ImageEntity> images(MovieEntity movieEntity) {
        return movieEntity.getImagesEntities();
    }

    @SchemaMapping(typeName = "Movie", field = "watchStatus")
    public List<WatchStatusEntity> watchStatus(MovieEntity movieEntity, Authentication authentication) {
        return watchStatusRepository.findByUserEntityExternalIdAndMovieEntity(authentication.getName(), movieEntity, Sort.by("dateUpdated").descending());
    }

    @SchemaMapping(typeName = "Movie", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(MovieEntity movieEntity) {
        return movieEntity.getMediaFileEntities();
    }

}
