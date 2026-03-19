package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.SeasonEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.SeasonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Controller
@RequiredArgsConstructor
public class SeasonController {
    private final SeasonRepository seasonRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<SeasonEntity> seasonById(@Argument UUID id) {
        return seasonRepository.findById(id);
    }

    @SchemaMapping(typeName = "Season", field = "show")
    public ShowEntity show(SeasonEntity seasonEntity) {
        return seasonEntity.getShowEntity();
    }

    @SchemaMapping(typeName = "Season", field = "episodes")
    public List<EpisodeEntity> season(SeasonEntity seasonEntity) {
        return seasonEntity.getEpisodeEntities();
    }

}
