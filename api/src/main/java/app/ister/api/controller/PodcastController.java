package app.ister.api.controller;

import app.ister.api.service.ItunesSearchService;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.LibraryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PodcastEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.eventdata.PodcastRefreshRequestedData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import app.ister.core.repository.PodcastRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ServerEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class PodcastController {
    private static final int DIRECTORY_SEARCH_LIMIT = 20;

    private final PodcastRepository podcastRepository;
    private final LibraryRepository libraryRepository;
    private final ImageRepository imageRepository;
    private final ItunesSearchService itunesSearchService;
    private final ServerEventService serverEventService;
    private final MessageSender messageSender;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PodcastEntity> podcastById(@Argument UUID id) {
        return podcastRepository.findById(id);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<PodcastEntity> podcasts(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId) {
        Pageable pageable = Paging.pageable(page, size, 20,
                sorting, SortingEnum.NAME, sortingOrder, SortingOrder.ASCENDING);
        // Podcasts have a "title" column where the other types have "name", and no "releaseYear"
        // at all — remap both onto "title" so an unsupported sort key degrades gracefully instead
        // of failing the query. Other keys (e.g. dateCreated) exist on the entity and pass through.
        java.util.Set<String> titleAliases = java.util.Set.of("name", "releaseYear");
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by(
                pageable.getSort().stream()
                        .map(order -> titleAliases.contains(order.getProperty())
                                ? order.withProperty("title") : order)
                        .toList());
        Pageable byTitle = org.springframework.data.domain.PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), sort);
        return libraryId
                .map(id -> podcastRepository.findByLibraryEntityIdAndActiveTrue(id, byTitle))
                .orElseGet(() -> podcastRepository.findByActiveTrue(byTitle));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<ItunesSearchService.DirectoryResult> searchPodcastDirectory(@Argument String term) {
        return itunesSearchService.search(term, DIRECTORY_SEARCH_LIMIT);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public PodcastEntity subscribePodcast(@Argument String feedUrl) {
        String url = feedUrl.strip();
        Optional<PodcastEntity> existing = podcastRepository.findByFeedUrl(url);
        PodcastEntity podcast;
        if (existing.isPresent()) {
            podcast = existing.get();
            podcast.setActive(true);
            podcastRepository.save(podcast);
        } else {
            LibraryEntity library = libraryRepository.findFirstByLibraryType(LibraryType.PODCAST)
                    .orElseThrow(() -> new IllegalStateException(
                            "No PODCAST library configured; add one via app.ister.disk.libraries"));
            podcast = PodcastEntity.builder()
                    .libraryEntity(library)
                    .feedUrl(url)
                    .title(url) // placeholder until the first refresh parses the feed
                    .active(true)
                    .build();
            podcastRepository.save(podcast);
            serverEventService.createPodcastFoundEvent(podcast.getId());
        }
        requestRefresh(podcast.getId());
        return podcast;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean unsubscribePodcast(@Argument UUID id) {
        PodcastEntity podcast = podcastRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Podcast not found: " + id));
        podcast.setActive(false);
        podcastRepository.save(podcast);
        serverEventService.createSearchDeleteEvent(SearchEntityType.PODCAST, podcast.getId());
        return true;
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean refreshPodcasts() {
        podcastRepository.findByActiveTrue().forEach(podcast -> requestRefresh(podcast.getId()));
        return true;
    }

    private void requestRefresh(UUID podcastId) {
        messageSender.sendPodcastRefreshRequested(PodcastRefreshRequestedData.builder()
                .eventType(EventType.PODCAST_REFRESH_REQUESTED)
                .podcastId(podcastId)
                .build());
    }

    @SchemaMapping(typeName = "Podcast", field = "metadata")
    public List<MetadataEntity> metadata(PodcastEntity podcastEntity) {
        return podcastEntity.getMetadataEntities();
    }

    @BatchMapping(typeName = "Podcast", field = "images")
    public Map<PodcastEntity, List<ImageEntity>> images(List<PodcastEntity> podcasts) {
        List<UUID> ids = podcasts.stream().map(PodcastEntity::getId).toList();
        Map<UUID, List<ImageEntity>> byPodcastId = imageRepository.findByPodcastEntityIdIn(ids).stream()
                .collect(Collectors.groupingBy(ImageEntity::getPodcastEntityId));
        return podcasts.stream().collect(Collectors.toMap(p -> p, p -> byPodcastId.getOrDefault(p.getId(), List.of())));
    }
}
