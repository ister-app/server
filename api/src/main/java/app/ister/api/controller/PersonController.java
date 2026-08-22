package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.TrackRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PersonController {
    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;
    private final LibraryRepository libraryRepository;
    private final CreditRepository creditRepository;
    private final BookRepository bookRepository;
    private final TrackRepository trackRepository;
    private final LibraryAccessService libraryAccessService;
    private final FilteredBrowse filteredBrowse;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PersonEntity> personById(@Argument UUID id, Authentication authentication) {
        return personRepository.findById(id)
                .filter(person -> canSeePerson(person, authentication));
    }

    /**
     * A person with a library follows normal library access. Persons created from TMDB cast
     * credits are library-less; they are visible when any of their credits lies in an accessible
     * library. Uses allowedLibraryIds (empty Optional = admin, sees everything) directly instead
     * of canAccess(UUID), whose null-check would deny before the admin bypass.
     */
    private boolean canSeePerson(PersonEntity person, Authentication authentication) {
        if (person.getLibraryEntity() != null) {
            return libraryAccessService.canAccess(person.getLibraryEntity(), authentication);
        }
        return libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> !allowed.isEmpty()
                        && creditRepository.hasCreditInLibraries(person.getId(), allowed))
                .orElse(true);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<PersonEntity> persons(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId,
            @Argument Optional<MediaFilter> filter, Authentication authentication) {
        Pageable pageable = Paging.pageable(page, size, 10,
                sorting, SortingEnum.NAME, sortingOrder, SortingOrder.ASCENDING);
        Optional<Page<PersonEntity>> filtered = filteredBrowse.page(
                FilterKind.ARTIST, filter, sorting.orElse(SortingEnum.NAME),
                sortingOrder.orElse(SortingOrder.ASCENDING), libraryId, pageable, authentication);
        if (filtered.isPresent()) {
            return filtered.get();
        }
        if (libraryId.isPresent()) {
            return libraryId.filter(id -> libraryAccessService.canAccess(id, authentication))
                    .flatMap(libraryRepository::findById)
                    .map(lib -> personRepository.findByLibraryEntity(lib, pageable))
                    .orElseGet(() -> Page.empty(pageable));
        }
        return libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> personRepository.findByLibraryEntityIdIn(allowed, pageable))
                .orElseGet(() -> personRepository.findAll(pageable));
    }

    @SchemaMapping(typeName = "Person", field = "albums")
    public List<AlbumEntity> albums(PersonEntity personEntity) {
        return personEntity.getAlbumEntities();
    }

    @SchemaMapping(typeName = "Person", field = "books")
    public List<BookEntity> books(PersonEntity personEntity) {
        return bookRepository.findByPersonEntityId(personEntity.getId());
    }

    @SchemaMapping(typeName = "Person", field = "metadata")
    public List<MetadataEntity> metadata(PersonEntity personEntity) {
        return personEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Person", field = "credits")
    public List<CreditEntity> credits(PersonEntity personEntity, Authentication authentication) {
        return libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> allowed.isEmpty()
                        ? List.<CreditEntity>of()
                        : creditRepository.findByPersonEntityIdInLibraries(personEntity.getId(), allowed))
                .orElseGet(() -> creditRepository.findByPersonEntityId(personEntity.getId(), Sort.by("castOrder")));
    }

    @SchemaMapping(typeName = "Person", field = "topPlayedTracks")
    public List<TrackEntity> topPlayedTracks(PersonEntity personEntity, @Argument Optional<Integer> limit, Authentication authentication) {
        int max = clampLimit(limit);
        return tracksInOrder(libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> allowed.isEmpty()
                        ? List.<UUID>of()
                        : trackRepository.findTopPlayedTrackIdsForPersonInLibraries(personEntity.getId(), authentication.getName(), allowed, Instant.now(), max, 0))
                .orElseGet(() -> trackRepository.findTopPlayedTrackIdsForPerson(personEntity.getId(), authentication.getName(), Instant.now(), max, 0)));
    }

    @SchemaMapping(typeName = "Person", field = "recentlyPlayedTracks")
    public List<TrackEntity> recentlyPlayedTracks(PersonEntity personEntity, @Argument Optional<Integer> limit, Authentication authentication) {
        int max = clampLimit(limit);
        return tracksInOrder(libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> allowed.isEmpty()
                        ? List.<UUID>of()
                        : trackRepository.findRecentlyPlayedTrackIdsForPersonInLibraries(personEntity.getId(), authentication.getName(), allowed, Instant.now(), max, 0))
                .orElseGet(() -> trackRepository.findRecentlyPlayedTrackIdsForPerson(personEntity.getId(), authentication.getName(), Instant.now(), max, 0)));
    }

    @SchemaMapping(typeName = "Person", field = "topRatedTracks")
    public List<TrackEntity> topRatedTracks(PersonEntity personEntity, @Argument Optional<Integer> limit, Authentication authentication) {
        int max = clampLimit(limit);
        return tracksInOrder(libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> allowed.isEmpty()
                        ? List.<UUID>of()
                        : trackRepository.findTopRatedTrackIdsForPersonInLibraries(personEntity.getId(), authentication.getName(), allowed, max, 0))
                .orElseGet(() -> trackRepository.findTopRatedTrackIdsForPerson(personEntity.getId(), authentication.getName(), max, 0)));
    }

    @SchemaMapping(typeName = "Person", field = "recentlyAddedTracks")
    public List<TrackEntity> recentlyAddedTracks(PersonEntity personEntity, @Argument Optional<Integer> limit, Authentication authentication) {
        int max = clampLimit(limit);
        return tracksInOrder(libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> allowed.isEmpty()
                        ? List.<UUID>of()
                        : trackRepository.findRecentlyAddedTrackIdsForPersonInLibraries(personEntity.getId(), allowed, Instant.now(), max, 0))
                .orElseGet(() -> trackRepository.findRecentlyAddedTrackIdsForPerson(personEntity.getId(), Instant.now(), max, 0)));
    }

    private static int clampLimit(Optional<Integer> limit) {
        return Math.clamp(limit.orElse(10), 1, 50);
    }

    /** Loads the tracks of the ranked id list, preserving the list's order. */
    private List<TrackEntity> tracksInOrder(List<UUID> ids) {
        Map<UUID, TrackEntity> byId = trackRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(TrackEntity::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    @BatchMapping(typeName = "Person", field = "images")
    public Map<PersonEntity, List<ImageEntity>> images(List<PersonEntity> persons) {
        List<UUID> ids = persons.stream().map(PersonEntity::getId).toList();
        Map<UUID, List<ImageEntity>> byPersonId = imageRepository.findByPersonEntityIdIn(ids).stream()
                .collect(Collectors.groupingBy(ImageEntity::getPersonEntityId));
        return persons.stream().collect(Collectors.toMap(a -> a, a -> byPersonId.getOrDefault(a.getId(), List.of())));
    }
}
