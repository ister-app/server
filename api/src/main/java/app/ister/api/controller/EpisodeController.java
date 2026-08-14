package app.ister.api.controller;

import app.ister.core.entity.*;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.filter.FilterKind;
import app.ister.core.filter.MediaFilter;
import app.ister.api.dto.MediaFilePart;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.MediaFileEpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.LibraryAccessService;
import app.ister.core.service.MediaFileEpisodeService;
import lombok.RequiredArgsConstructor;
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class EpisodeController {
    private final EpisodeRepository episodeRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final LibraryAccessService libraryAccessService;
    private final LibraryRepository libraryRepository;
    private final MediaFileEpisodeRepository mediaFileEpisodeRepository;
    private final MediaFileEpisodeService mediaFileEpisodeService;
    private final FilteredBrowse filteredBrowse;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<EpisodeEntity> episodeById(@Argument UUID id, Authentication authentication) {
        return episodeRepository.findById(id)
                .filter(episode -> libraryAccessService.canAccess(
                        episode.getShowEntity().getLibraryEntity(), authentication));
    }

    // Newest-added first by default: the library-wide episode list reads as a feed of new arrivals.
    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<EpisodeEntity> episodes(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId,
            @Argument Optional<MediaFilter> filter, Authentication authentication) {
        Pageable pageable = Paging.unsorted(page, size, 20);
        SortingEnum sort = sorting.orElse(SortingEnum.DATE_CREATED);
        SortingOrder order = sortingOrder.orElse(SortingOrder.DESCENDING);
        Optional<Page<EpisodeEntity>> filtered = filteredBrowse.page(
                FilterKind.EPISODE, filter, sort, order, libraryId, pageable, authentication);
        if (filtered.isPresent()) {
            return filtered.get();
        }
        if (libraryId.isPresent()) {
            return libraryId.filter(id -> libraryAccessService.canAccess(id, authentication))
                    .map(id -> episodeRepository.findInLibraries(List.of(id), sort, order, pageable))
                    .orElseGet(() -> Page.empty(pageable));
        }
        Collection<UUID> libraries = libraryAccessService.allowedLibraryIds(authentication)
                .<Collection<UUID>>map(allowed -> allowed)
                .orElseGet(() -> libraryRepository.findAll().stream().map(LibraryEntity::getId).toList());
        return episodeRepository.findInLibraries(libraries, sort, order, pageable);
    }

    @SchemaMapping(typeName = "Episode", field = "show")
    public ShowEntity show(EpisodeEntity episodeEntity) {
        return episodeEntity.getShowEntity();
    }

    @SchemaMapping(typeName = "Episode", field = "season")
    public SeasonEntity season(EpisodeEntity episodeEntity) {
        return episodeEntity.getSeasonEntity();
    }

    @SchemaMapping(typeName = "Episode", field = "metadata")
    public List<MetadataEntity> metadata(EpisodeEntity episodeEntity) {
        return episodeEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Episode", field = "images")
    public List<ImageEntity> images(EpisodeEntity episodeEntity) {
        return episodeEntity.getImagesEntities();
    }

    @BatchMapping(typeName = "Episode", field = "watchStatus")
    public Map<EpisodeEntity, List<WatchStatusEntity>> watchStatus(List<EpisodeEntity> episodes, Authentication authentication) {
        Map<UUID, List<WatchStatusEntity>> byEpisodeId = watchStatusRepository
                .findByUserEntityExternalIdAndEpisodeEntityIn(authentication.getName(), episodes, Sort.by("dateUpdated").descending()).stream()
                .collect(Collectors.groupingBy(w -> w.getEpisodeEntity().getId()));
        return episodes.stream().collect(Collectors.toMap(e -> e, e -> byEpisodeId.getOrDefault(e.getId(), List.of())));
    }

    // Via MediaFileEpisodeService: an episode inside a multi-episode file (s04e06-e07.mkv) only
    // reaches its file through the link table, not through the direct FK.
    @SchemaMapping(typeName = "Episode", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(EpisodeEntity episodeEntity) {
        return mediaFileEpisodeService.filesForEpisode(episodeEntity.getId());
    }

    @SchemaMapping(typeName = "Episode", field = "mediaFileParts")
    public List<MediaFilePart> mediaFileParts(EpisodeEntity episodeEntity) {
        return mediaFileEpisodeService.filesForEpisode(episodeEntity.getId()).stream()
                .map(file -> mediaFileEpisodeService.segmentFor(file.getId(), episodeEntity.getId())
                        .map(segment -> new MediaFilePart(file, segment.getStartInMilliseconds(), segment.getDurationInMilliseconds()))
                        .orElseGet(() -> new MediaFilePart(file, 0, file.getDurationInMilliseconds())))
                .toList();
    }

    @SchemaMapping(typeName = "MediaFile", field = "mediaFileStreams")
    public List<MediaFileStreamEntity> mediaFileStreams(MediaFileEntity mediaFileEntity) {
        return mediaFileEntity.getMediaFileStreamEntity();
    }

    @SchemaMapping(typeName = "MediaFile", field = "episodes")
    public List<EpisodeEntity> episodes(MediaFileEntity mediaFileEntity) {
        List<MediaFileEpisodeEntity> links = mediaFileEpisodeRepository.findByMediaFileEntityIdOrderByPartNumber(mediaFileEntity.getId());
        if (!links.isEmpty()) {
            // At most three links per file; resolve one by one to keep the part order.
            return links.stream()
                    .map(link -> episodeRepository.findById(link.getEpisodeEntityId()))
                    .flatMap(Optional::stream)
                    .toList();
        }
        if (mediaFileEntity.getEpisodeEntity() == null) {
            return List.of();
        }
        return List.of(mediaFileEntity.getEpisodeEntity());
    }

}
