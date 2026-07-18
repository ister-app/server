package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.entity.UserSeriesPreferenceEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.enums.ReadingDirection;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.SeriesRepository;
import app.ister.core.repository.UserSeriesPreferenceRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.SeriesPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
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

@Controller
@RequiredArgsConstructor
public class SeriesController {
    private final SeriesRepository seriesRepository;
    private final ImageRepository imageRepository;
    private final MetadataRepository metadataRepository;
    private final UserSeriesPreferenceRepository userSeriesPreferenceRepository;
    private final SeriesPreferenceService seriesPreferenceService;
    private final LibraryAccessService libraryAccessService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<SeriesEntity> seriesById(@Argument UUID id, Authentication authentication) {
        return seriesRepository.findById(id)
                .filter(series -> libraryAccessService.canAccess(series.getLibraryEntity(), authentication));
    }

    /** The browse grid of a COMIC library: series, not loose volumes. */
    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<SeriesEntity> series(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId, Authentication authentication) {
        Pageable pageable = Paging.pageable(page, size, 20,
                sorting, SortingEnum.NAME, sortingOrder, SortingOrder.ASCENDING);
        if (libraryId.isPresent()) {
            return libraryId.filter(id -> libraryAccessService.canAccess(id, authentication))
                    .map(id -> seriesRepository.findByLibraryEntityId(id, pageable))
                    .orElseGet(() -> Page.empty(pageable));
        }
        return libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> seriesRepository.findByLibraryEntityIdIn(allowed, pageable))
                .orElseGet(() -> seriesRepository.findAll(pageable));
    }

    @SchemaMapping(typeName = "Series", field = "metadata")
    public List<MetadataEntity> metadata(SeriesEntity seriesEntity) {
        return metadataRepository.findBySeriesEntityId(seriesEntity.getId());
    }

    @SchemaMapping(typeName = "Series", field = "images")
    public List<ImageEntity> images(SeriesEntity seriesEntity) {
        return imageRepository.findBySeriesEntityId(seriesEntity.getId());
    }

    @SchemaMapping(typeName = "Series", field = "author")
    public PersonEntity author(SeriesEntity seriesEntity) {
        return seriesEntity.getPersonEntity();
    }

    /** Series order comes from the entity's @OrderBy: seriesIndex ascending, unknown last. */
    @SchemaMapping(typeName = "Series", field = "books")
    public List<BookEntity> books(SeriesEntity seriesEntity) {
        return seriesEntity.getBookEntities();
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean setSeriesReadingDirection(
            @Argument UUID seriesId,
            @Argument ReadingDirection direction,
            Authentication authentication) {
        seriesPreferenceService.setReadingDirection(authentication, seriesId, direction);
        return true;
    }

    /**
     * Series without a preference row fall back to their detected default, else LTR, so the
     * non-null GraphQL field always resolves.
     */
    @BatchMapping(typeName = "Series", field = "readingDirection")
    public Map<SeriesEntity, ReadingDirection> readingDirection(
            List<SeriesEntity> series, Authentication authentication) {
        Map<UUID, ReadingDirection> byId = preferences(series, authentication).stream()
                .collect(Collectors.toMap(p -> p.getSeriesEntity().getId(),
                        UserSeriesPreferenceEntity::getReadingDirection));
        return series.stream().collect(Collectors.toMap(
                entity -> entity,
                entity -> SeriesPreferenceService.resolve(byId.get(entity.getId()), entity)));
    }

    /** Series absent from the map resolve to null: no explicit override set. */
    @BatchMapping(typeName = "Series", field = "userReadingDirection")
    public Map<SeriesEntity, ReadingDirection> userReadingDirection(
            List<SeriesEntity> series, Authentication authentication) {
        Map<UUID, ReadingDirection> byId = preferences(series, authentication).stream()
                .collect(Collectors.toMap(p -> p.getSeriesEntity().getId(),
                        UserSeriesPreferenceEntity::getReadingDirection));
        return series.stream()
                .filter(entity -> byId.containsKey(entity.getId()))
                .collect(Collectors.toMap(entity -> entity, entity -> byId.get(entity.getId())));
    }

    private List<UserSeriesPreferenceEntity> preferences(
            List<SeriesEntity> series, Authentication authentication) {
        return userSeriesPreferenceRepository
                .findByUserEntityExternalIdAndSeriesEntityIn(authentication.getName(), series);
    }

    /** The series' own artwork (folder.jpg, wiki thumbnail) wins; else the first book's cover. */
    @SchemaMapping(typeName = "Series", field = "cover")
    public ImageEntity cover(SeriesEntity seriesEntity) {
        Optional<ImageEntity> own = imageRepository.findBySeriesEntityId(seriesEntity.getId()).stream()
                .filter(image -> image.getType() == ImageType.COVER)
                .findFirst();
        return own.orElseGet(() -> seriesEntity.getBookEntities().stream()
                .flatMap(book -> imageRepository.findByBookEntityId(book.getId()).stream())
                .filter(image -> image.getType() == ImageType.COVER)
                .findFirst()
                .orElse(null));
    }
}
