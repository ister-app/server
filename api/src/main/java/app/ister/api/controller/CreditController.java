package app.ister.api.controller;

import app.ister.core.entity.BaseEntity;
import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.CreditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class CreditController {
    private static final Comparator<CreditEntity> BY_CAST_ORDER =
            Comparator.comparing(CreditEntity::getCastOrder, Comparator.nullsLast(Comparator.naturalOrder()));

    private final CreditRepository creditRepository;

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
