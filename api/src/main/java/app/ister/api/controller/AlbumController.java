package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.Arguments;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AlbumController {
    private final AlbumRepository albumRepository;
    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;
    private final LibraryRepository libraryRepository;
    private final LibraryAccessService libraryAccessService;
    private final FilteredBrowse filteredBrowse;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<AlbumEntity> albumById(@Argument UUID id, Authentication authentication) {
        return albumRepository.findById(id)
                .filter(album -> libraryAccessService.canAccess(album.getLibraryEntity(), authentication));
    }

    /** All arguments of the {@code albums} query, bound as one object off the argument map. */
    record AlbumsArguments(Optional<Integer> page, Optional<Integer> size,
                           Optional<SortingEnum> sorting, Optional<SortingOrder> sortingOrder,
                           Optional<UUID> artistId, Optional<UUID> appearsOnArtistId,
                           Optional<UUID> libraryId, Optional<MediaFilter> filter) {
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<AlbumEntity> albums(@Arguments AlbumsArguments args, Authentication authentication) {
        Pageable pageable = Paging.pageable(args.page(), args.size(), 20,
                args.sorting(), SortingEnum.NAME, args.sortingOrder(), SortingOrder.ASCENDING);
        Optional<Page<AlbumEntity>> filtered = filteredBrowse.page(
                FilterKind.ALBUM, args.filter(), args.sorting().orElse(SortingEnum.NAME),
                args.sortingOrder().orElse(SortingOrder.ASCENDING), args.libraryId(), pageable, authentication);
        if (filtered.isPresent()) {
            return filtered.get();
        }
        // Albums the artist is credited on but does not own: compilations and guest appearances.
        Optional<UUID> appearsOnArtistId = args.appearsOnArtistId();
        if (appearsOnArtistId.isPresent()) {
            Collection<UUID> libraries = visibleLibraryIds(args.libraryId(), authentication);
            return libraries.isEmpty() ? Page.empty(pageable)
                    : albumRepository.findAppearsOnForPerson(appearsOnArtistId.get(), libraries, pageable);
        }
        Optional<UUID> artistId = args.artistId();
        if (artistId.isPresent()) {
            return personRepository.findById(artistId.get())
                    .map(artist -> libraryAccessService.allowedLibraryIds(authentication)
                            .map(allowed -> albumRepository.findByPersonEntityAndLibraryEntityIdIn(artist, allowed, pageable))
                            .orElseGet(() -> albumRepository.findByPersonEntity(artist, pageable)))
                    .orElseGet(() -> Page.empty(pageable));
        }
        if (args.libraryId().isPresent()) {
            return args.libraryId().filter(id -> libraryAccessService.canAccess(id, authentication))
                    .map(id -> albumRepository.findByLibraryEntityId(id, pageable))
                    .orElseGet(() -> Page.empty(pageable));
        }
        return libraryAccessService.allowedLibraryIds(authentication)
                .map(allowed -> albumRepository.findByLibraryEntityIdIn(allowed, pageable))
                .orElseGet(() -> albumRepository.findAll(pageable));
    }

    /**
     * The libraries this query may read: the requested one if the user may see it, otherwise every
     * library they are allowed to see. Empty means "nothing to return".
     */
    private Collection<UUID> visibleLibraryIds(Optional<UUID> libraryId, Authentication authentication) {
        if (libraryId.isPresent()) {
            return libraryAccessService.canAccess(libraryId.get(), authentication)
                    ? List.of(libraryId.get()) : List.of();
        }
        return libraryAccessService.allowedLibraryIds(authentication)
                .<Collection<UUID>>map(allowed -> allowed)
                .orElseGet(() -> libraryRepository.findAll().stream().map(LibraryEntity::getId).toList());
    }

    @SchemaMapping(typeName = "Album", field = "artist")
    public PersonEntity artist(AlbumEntity albumEntity) {
        return albumEntity.getPersonEntity();
    }

    /**
     * The album's own release year is only set when the directory name carries a "(YYYY)" suffix;
     * for everything else the year lives in the metadata parsed from the audio tags or MusicBrainz.
     */
    @SchemaMapping(typeName = "Album", field = "releaseYear")
    public int releaseYear(AlbumEntity albumEntity) {
        if (albumEntity.getReleaseYear() > 0) {
            return albumEntity.getReleaseYear();
        }
        return albumEntity.getMetadataEntities().stream()
                .map(MetadataEntity::getReleased)
                .filter(Objects::nonNull)
                .mapToInt(LocalDate::getYear)
                .min()
                .orElse(0);
    }

    @SchemaMapping(typeName = "Album", field = "dateAdded")
    public String dateAdded(AlbumEntity albumEntity) {
        return albumEntity.getDateCreated() == null ? null : albumEntity.getDateCreated().toString();
    }

    @SchemaMapping(typeName = "Album", field = "tracks")
    public List<TrackEntity> tracks(AlbumEntity albumEntity) {
        return albumEntity.getTrackEntities();
    }

    @SchemaMapping(typeName = "Album", field = "metadata")
    public List<MetadataEntity> metadata(AlbumEntity albumEntity) {
        return albumEntity.getMetadataEntities();
    }

    @BatchMapping(typeName = "Album", field = "images")
    public Map<AlbumEntity, List<ImageEntity>> images(List<AlbumEntity> albums) {
        List<UUID> ids = albums.stream().map(AlbumEntity::getId).toList();
        Map<UUID, List<ImageEntity>> byAlbumId = imageRepository.findByAlbumEntityIdIn(ids).stream()
                .collect(Collectors.groupingBy(ImageEntity::getAlbumEntityId));
        return albums.stream().collect(Collectors.toMap(a -> a, a -> byAlbumId.getOrDefault(a.getId(), List.of())));
    }
}
