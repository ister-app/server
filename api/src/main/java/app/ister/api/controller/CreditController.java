package app.ister.api.controller;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class CreditController {
    private static final Comparator<CreditEntity> BY_CAST_ORDER =
            Comparator.comparing(CreditEntity::getCastOrder, Comparator.nullsLast(Comparator.naturalOrder()));

    private final CreditRepository creditRepository;
    private final MovieRepository movieRepository;
    private final ShowRepository showRepository;
    private final EpisodeRepository episodeRepository;

    @BatchMapping(typeName = "Movie", field = "cast")
    public Map<MovieEntity, List<CreditEntity>> movieCast(List<MovieEntity> movies) {
        return groupByParent(movies, creditRepository.findByMovieEntityIdIn(ids(movies)), CreditEntity::getMovieEntityId);
    }

    @BatchMapping(typeName = "Show", field = "cast")
    public Map<ShowEntity, List<CreditEntity>> showCast(List<ShowEntity> shows) {
        return groupByParent(shows, creditRepository.findByShowEntityIdIn(ids(shows)), CreditEntity::getShowEntityId);
    }

    @BatchMapping(typeName = "Episode", field = "cast")
    public Map<EpisodeEntity, List<CreditEntity>> episodeCast(List<EpisodeEntity> episodes) {
        return groupByParent(episodes, creditRepository.findByEpisodeEntityIdIn(ids(episodes)), CreditEntity::getEpisodeEntityId);
    }

    @SchemaMapping(typeName = "Credit", field = "person")
    public PersonEntity person(CreditEntity creditEntity) {
        return creditEntity.getPersonEntity();
    }

    @BatchMapping(typeName = "Credit", field = "movie")
    public Map<CreditEntity, MovieEntity> creditMovie(List<CreditEntity> credits) {
        return resolveParents(credits, CreditEntity::getMovieEntityId, movieRepository::findAllById);
    }

    @BatchMapping(typeName = "Credit", field = "show")
    public Map<CreditEntity, ShowEntity> creditShow(List<CreditEntity> credits) {
        return resolveParents(credits, CreditEntity::getShowEntityId, showRepository::findAllById);
    }

    @BatchMapping(typeName = "Credit", field = "episode")
    public Map<CreditEntity, EpisodeEntity> creditEpisode(List<CreditEntity> credits) {
        return resolveParents(credits, CreditEntity::getEpisodeEntityId, episodeRepository::findAllById);
    }

    private static <P extends BaseEntity> Map<CreditEntity, P> resolveParents(
            List<CreditEntity> credits, Function<CreditEntity, UUID> parentId, Function<Iterable<UUID>, List<P>> loadByIds) {
        List<UUID> parentIds = credits.stream().map(parentId).filter(Objects::nonNull).distinct().toList();
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, P> byId = loadByIds.apply(parentIds).stream()
                .collect(Collectors.toMap(BaseEntity::getId, Function.identity()));
        Map<CreditEntity, P> result = new HashMap<>();
        for (CreditEntity credit : credits) {
            P parent = byId.get(parentId.apply(credit));
            if (parent != null) {
                result.put(credit, parent);
            }
        }
        return result;
    }

    private static <P extends BaseEntity> List<UUID> ids(List<P> parents) {
        return parents.stream().map(BaseEntity::getId).toList();
    }

    private static <P extends BaseEntity> Map<P, List<CreditEntity>> groupByParent(
            List<P> parents, List<CreditEntity> credits, Function<CreditEntity, UUID> parentId) {
        Map<UUID, List<CreditEntity>> byParentId = credits.stream()
                .sorted(BY_CAST_ORDER)
                .collect(Collectors.groupingBy(parentId));
        return parents.stream()
                .collect(Collectors.toMap(p -> p, p -> byParentId.getOrDefault(p.getId(), List.of())));
    }
}
