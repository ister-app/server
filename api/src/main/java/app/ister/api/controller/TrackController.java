package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackCreditEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.enums.TrackCreditType;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.TrackCreditRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.repository.WatchStatusRepository;
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

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class TrackController {
    private final TrackRepository trackRepository;
    private final TrackCreditRepository trackCreditRepository;
    private final PersonRepository personRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final LibraryAccessService libraryAccessService;
    private final LibraryRepository libraryRepository;
    private final FilteredBrowse filteredBrowse;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<TrackEntity> trackById(@Argument UUID id, Authentication authentication) {
        return trackRepository.findById(id)
                .filter(track -> libraryAccessService.canAccess(
                        track.getAlbumEntity().getLibraryEntity(), authentication));
    }

    /** All arguments of the {@code tracks} query, bound as one object off the argument map. */
    record TracksArguments(Optional<Integer> page, Optional<Integer> size,
                           Optional<SortingEnum> sorting, Optional<SortingOrder> sortingOrder,
                           Optional<UUID> artistId, Optional<UUID> libraryId,
                           Optional<MediaFilter> filter) {
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<TrackEntity> tracks(@Arguments TracksArguments args, Authentication authentication) {
        Pageable pageable = Paging.unsorted(args.page(), args.size(), 20);
        SortingEnum sort = args.sorting().orElse(SortingEnum.NAME);
        SortingOrder order = args.sortingOrder().orElse(SortingOrder.ASCENDING);
        Optional<Page<TrackEntity>> filtered = filteredBrowse.page(
                FilterKind.TRACK, args.filter(), sort, order, args.libraryId(), pageable, authentication);
        if (filtered.isPresent()) {
            return filtered.get();
        }
        Collection<UUID> libraries = visibleLibraryIds(args.libraryId(), authentication);
        if (libraries.isEmpty()) {
            return Page.empty(pageable);
        }
        return args.artistId()
                .map(artistId -> trackRepository.findForPersonInLibraries(artistId, libraries, sort, order, pageable))
                .orElseGet(() -> trackRepository.findInLibraries(libraries, sort, order, pageable));
    }

    /**
     * The libraries this query may read: the requested one if the user may see it, otherwise every
     * library they are allowed to see. Empty means "nothing to return" — which also keeps an
     * {@code IN ()} out of the generated SQL.
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

    // Since tracks carry their own tag-derived artist, an album's tracks no longer share one
    // person; batch-load to keep album pages at a single person query.
    @BatchMapping(typeName = "Track", field = "artist")
    public Map<TrackEntity, PersonEntity> artist(List<TrackEntity> tracks) {
        List<UUID> personIds = tracks.stream().map(t -> t.getPersonEntity().getId()).distinct().toList();
        Map<UUID, PersonEntity> byId = personRepository.findAllById(personIds).stream()
                .collect(Collectors.toMap(PersonEntity::getId, Function.identity()));
        Map<TrackEntity, PersonEntity> result = new HashMap<>();
        tracks.forEach(t -> result.put(t, byId.get(t.getPersonEntity().getId())));
        return result;
    }

    // One credit query plus one person query per page, whatever the number of guests.
    @BatchMapping(typeName = "Track", field = "artists")
    public Map<TrackEntity, List<TrackCreditEntity>> artists(List<TrackEntity> tracks) {
        List<UUID> trackIds = tracks.stream().map(TrackEntity::getId).toList();
        Map<UUID, List<TrackCreditEntity>> byTrackId = trackCreditRepository.findByTrackEntity_IdIn(trackIds).stream()
                .collect(Collectors.groupingBy(credit -> credit.getTrackEntity().getId()));
        Map<TrackEntity, List<TrackCreditEntity>> result = new HashMap<>();
        tracks.forEach(t -> result.put(t, byTrackId.getOrDefault(t.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(TrackCreditEntity::getPosition))
                .toList()));
        return result;
    }

    @SchemaMapping(typeName = "TrackCredit", field = "person")
    public PersonEntity creditPerson(TrackCreditEntity credit) {
        return credit.getPersonEntity();
    }

    @SchemaMapping(typeName = "TrackCredit", field = "type")
    public TrackCreditType creditType(TrackCreditEntity credit) {
        return credit.getCreditType();
    }

    @BatchMapping(typeName = "Track", field = "playCount")
    public Map<TrackEntity, Integer> playCount(List<TrackEntity> tracks, Authentication authentication) {
        Map<UUID, WatchStatusRepository.TrackPlayStats> stats = playStatsByTrackId(tracks, authentication);
        Map<TrackEntity, Integer> result = new HashMap<>();
        tracks.forEach(t -> result.put(t, Optional.ofNullable(stats.get(t.getId()))
                .map(s -> (int) s.getPlays()).orElse(null)));
        return result;
    }

    @BatchMapping(typeName = "Track", field = "lastPlayedAt")
    public Map<TrackEntity, String> lastPlayedAt(List<TrackEntity> tracks, Authentication authentication) {
        Map<UUID, WatchStatusRepository.TrackPlayStats> stats = playStatsByTrackId(tracks, authentication);
        Map<TrackEntity, String> result = new HashMap<>();
        tracks.forEach(t -> result.put(t, Optional.ofNullable(stats.get(t.getId()))
                .map(s -> s.getLastPlayedAt().toString()).orElse(null)));
        return result;
    }

    private Map<UUID, WatchStatusRepository.TrackPlayStats> playStatsByTrackId(List<TrackEntity> tracks, Authentication authentication) {
        List<UUID> trackIds = tracks.stream().map(TrackEntity::getId).toList();
        return watchStatusRepository.findTrackPlayStats(authentication.getName(), trackIds).stream()
                .collect(Collectors.toMap(WatchStatusRepository.TrackPlayStats::getTrackId, Function.identity()));
    }

    @SchemaMapping(typeName = "Track", field = "album")
    public AlbumEntity album(TrackEntity trackEntity) {
        return trackEntity.getAlbumEntity();
    }

    @SchemaMapping(typeName = "Track", field = "metadata")
    public List<MetadataEntity> metadata(TrackEntity trackEntity) {
        return trackEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Track", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(TrackEntity trackEntity) {
        return trackEntity.getMediaFileEntities();
    }
}
