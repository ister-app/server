package app.ister.api.controller;

import app.ister.api.error.SearchUnavailableException;
import app.ister.core.entity.BaseEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ShowRepository;
import app.ister.core.repository.TrackRepository;
import app.ister.core.service.ServerEventService;
import app.ister.search.SearchQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class SearchController {
    private static final int DEFAULT_SIZE = 20;

    // Provider instead of direct injection: the service only exists when Typesense is enabled.
    private final ObjectProvider<SearchQueryService> searchQueryService;
    private final ServerEventService serverEventService;
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;
    private final PersonRepository personRepository;
    private final AlbumRepository albumRepository;
    private final TrackRepository trackRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<Object> search(
            @Argument String term,
            @Argument Optional<Integer> size,
            @Argument Optional<UUID> libraryId) {
        SearchQueryService service = searchQueryService.getIfAvailable();
        if (service == null) {
            throw new SearchUnavailableException();
        }
        List<SearchQueryService.SearchHit> hits =
                service.search(term, size.orElse(DEFAULT_SIZE), libraryId.orElse(null));
        return hydrate(hits);
    }

    @PreAuthorize("hasRole('user')")
    @MutationMapping
    public Boolean reindexSearch() {
        if (searchQueryService.getIfAvailable() == null) {
            throw new SearchUnavailableException();
        }
        log.debug("Start reindexSearch");
        serverEventService.createSearchReindexEvent();
        return true;
    }

    /** Loads the hit entities from the database (one query per type), keeping Typesense ranking. */
    private List<Object> hydrate(List<SearchQueryService.SearchHit> hits) {
        Map<SearchEntityType, List<UUID>> idsByType = hits.stream()
                .collect(Collectors.groupingBy(
                        SearchQueryService.SearchHit::entityType,
                        Collectors.mapping(SearchQueryService.SearchHit::entityId, Collectors.toList())));
        Map<UUID, Object> entitiesById = new HashMap<>();
        idsByType.forEach((type, ids) ->
                repositoryFor(type).findAllById(ids).forEach(entity -> entitiesById.put(entity.getId(), entity)));
        return hits.stream()
                .map(hit -> entitiesById.get(hit.entityId()))
                .filter(Objects::nonNull) // index can lag behind deletes
                .toList();
    }

    private JpaRepository<? extends BaseEntity, UUID> repositoryFor(SearchEntityType entityType) {
        return switch (entityType) {
            case MOVIE -> movieRepository;
            case SHOW -> showRepository;
            case EPISODE -> episodeRepository;
            case PERSON -> personRepository;
            case ALBUM -> albumRepository;
            case TRACK -> trackRepository;
        };
    }
}
