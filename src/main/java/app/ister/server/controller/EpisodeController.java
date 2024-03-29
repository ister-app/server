package app.ister.server.controller;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.WatchStatusRepository;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("episodes")
@SecurityRequirement(name = "oidc_auth")
public class EpisodeController {
    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private WatchStatusRepository watchStatusRepository;

    @GetMapping(value = "/recent")
    public List<EpisodeEntity> getRecent() {
        List<EpisodeEntity> result = new ArrayList<>();
        for (String[] strings : watchStatusRepository.findRecentEpisodesAndShowIds()) {
            List<EpisodeEntity> seasonEpisodes = episodeRepository.findByShowEntityId(
                    UUID.fromString(strings[1]),
                    Sort.by("SeasonEntityNumber").ascending().and(
                            Sort.by("number").ascending()));
            Optional<EpisodeEntity> optionalEpisodeEntity = seasonEpisodes.stream().filter(episodeEntityInline -> episodeEntityInline.getId().equals(UUID.fromString(strings[0]))).findFirst();
            optionalEpisodeEntity.flatMap(episodeEntity -> getFirstUnwatchedEpisode(seasonEpisodes, episodeEntity)).ifPresent(result::add);
        }
        return result;
    }

    private Optional<EpisodeEntity> getFirstUnwatchedEpisode(List<EpisodeEntity> seasonEpisodes, EpisodeEntity episodeEntity) {
        if (!episodeEntity.getWatchStatusEntities().get(0).isWatched()) {
            return Optional.of(episodeEntity);
        } else {
            int indexOfNextOne = seasonEpisodes.indexOf(episodeEntity) + 1;
            if (seasonEpisodes.size() > indexOfNextOne) {
                EpisodeEntity nextEpisodeEntity = seasonEpisodes.get(indexOfNextOne);
                if (nextEpisodeEntity.getWatchStatusEntities().isEmpty() || !nextEpisodeEntity.getWatchStatusEntities().get(0).isWatched()) {
                    return Optional.of(nextEpisodeEntity);
                } else {
                    return getFirstUnwatchedEpisode(seasonEpisodes, nextEpisodeEntity);
                }
            } else {
                return Optional.empty();
            }
        }
    }

    @GetMapping(value = "/{id}")
    public Optional<EpisodeEntity> getEpisode(@PathVariable UUID id) {
        return episodeRepository.findById(id);
    }
}
