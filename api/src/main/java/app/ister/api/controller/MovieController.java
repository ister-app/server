package app.ister.api.controller;

import app.ister.core.entity.*;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class MovieController {
    private final MovieRepository movieRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final LibraryRepository libraryRepository;
    private final LibraryAccessService libraryAccessService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<MovieEntity> movies(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId, Authentication authentication) {
        Pageable pageable = Paging.pageable(page, size, 10,
                sorting, SortingEnum.DATE_CREATED, sortingOrder, SortingOrder.DESCENDING);
        if (libraryId.isPresent()) {
            return libraryId.filter(id -> libraryAccessService.canAccess(id, authentication))
                    .flatMap(libraryRepository::findById)
                    .map(lib -> movieRepository.findByLibraryEntity(lib, pageable))
                    .orElseGet(() -> Page.empty(pageable));
        }
        return libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> movieRepository.findByLibraryEntityIdIn(allowed, pageable))
                .orElseGet(() -> movieRepository.findAll(pageable));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<MovieEntity> movieById(@Argument UUID id, Authentication authentication) {
        return movieRepository.findById(id)
                .filter(movie -> libraryAccessService.canAccess(movie.getLibraryEntity(), authentication));
    }

    @SchemaMapping(typeName = "Movie", field = "metadata")
    public List<MetadataEntity> metadata(MovieEntity movieEntity) {
        return movieEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Movie", field = "images")
    public List<ImageEntity> images(MovieEntity movieEntity) {
        return movieEntity.getImagesEntities();
    }

    @BatchMapping(typeName = "Movie", field = "watchStatus")
    public Map<MovieEntity, List<WatchStatusEntity>> watchStatus(List<MovieEntity> movies, Authentication authentication) {
        Map<UUID, List<WatchStatusEntity>> byMovieId = watchStatusRepository
                .findByUserEntityExternalIdAndMovieEntityIn(authentication.getName(), movies, Sort.by("dateUpdated").descending()).stream()
                .collect(Collectors.groupingBy(w -> w.getMovieEntity().getId()));
        return movies.stream().collect(Collectors.toMap(m -> m, m -> byMovieId.getOrDefault(m.getId(), List.of())));
    }

    @SchemaMapping(typeName = "Movie", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(MovieEntity movieEntity) {
        return movieEntity.getMediaFileEntities();
    }

}
