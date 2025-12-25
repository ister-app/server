package app.ister.api.controller;

import app.ister.core.entitiy.*;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
public class EpisodeController {
    private final EpisodeRepository episodeRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final UserService userService;

    public EpisodeController(EpisodeRepository episodeRepository,
                             WatchStatusRepository watchStatusRepository,
                             UserService userService) {
        this.episodeRepository = episodeRepository;
        this.watchStatusRepository = watchStatusRepository;
        this.userService = userService;
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<EpisodeEntity> episodesRecentWatched(Authentication authentication) {
        log.debug("Getting recent episode list for user: {}", authentication.getName());
        UserEntity userEntity = userService.getOrCreateUser(authentication);

        return watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(userEntity.getId())
                .stream()
                .flatMap(strings -> getUnwatchedEpisodes(UUID.fromString(strings[1]), UUID.fromString(strings[0])).stream())
                .collect(Collectors.toList());
    }

    private List<EpisodeEntity> getUnwatchedEpisodes(UUID showId, UUID episodeId) {
        List<EpisodeEntity> seasonEpisodes = episodeRepository.findByShowEntityId(showId,
                Sort.by("seasonEntity.number").ascending().and(Sort.by("number").ascending()));

        return seasonEpisodes.stream()
                .filter(episode -> episode.getId().equals(episodeId))
                .map(episodeEntity -> getFirstUnwatchedEpisodeFrom(episodeEntity, seasonEpisodes))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());
    }

    private Optional<EpisodeEntity> getFirstUnwatchedEpisodeFrom(EpisodeEntity episodeEntity, List<EpisodeEntity> seasonEpisodes) {
        if (!episodeEntity.getWatchStatusEntities().getFirst().isWatched()) {
            return Optional.of(episodeEntity);
        }

        return findNextUnwatchedEpisode(episodeEntity, seasonEpisodes);
    }

    private Optional<EpisodeEntity> findNextUnwatchedEpisode(EpisodeEntity episodeEntity, List<EpisodeEntity> seasonEpisodes) {
        int indexOfNextOne = seasonEpisodes.indexOf(episodeEntity) + 1;

        if (seasonEpisodes.size() > indexOfNextOne) {
            EpisodeEntity nextEpisodeEntity = seasonEpisodes.get(indexOfNextOne);
            if (isUnwatchedOrOld(nextEpisodeEntity)) {
                return Optional.of(nextEpisodeEntity);
            }
            return findNextUnwatchedEpisode(nextEpisodeEntity, seasonEpisodes);
        }
        return Optional.empty();
    }

    private boolean isUnwatchedOrOld(EpisodeEntity episodeEntity) {
        return episodeEntity.getWatchStatusEntities().isEmpty() ||
                !episodeEntity.getWatchStatusEntities().getFirst().isWatched() ||
                episodeEntity.getWatchStatusEntities().getFirst().getDateUpdated().isBefore(Instant.now().minus(Duration.ofDays(300)));
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<EpisodeEntity> episodeById(@Argument UUID id) {
        return episodeRepository.findById(id);
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

    @SchemaMapping(typeName = "Episode", field = "watchStatus")
    public List<WatchStatusEntity> watchStatus(EpisodeEntity episodeEntity, Authentication authentication) {
        return watchStatusRepository.findByUserEntityExternalIdAndEpisodeEntity(authentication.getName(), episodeEntity, Sort.by("dateUpdated").descending());
    }

    @SchemaMapping(typeName = "Episode", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(EpisodeEntity episodeEntity) {
        return episodeEntity.getMediaFileEntities();
    }

    @SchemaMapping(typeName = "MediaFile", field = "mediaFileStreams")
    public List<MediaFileStreamEntity> mediaFileStreams(MediaFileEntity mediaFileEntity) {
        return mediaFileEntity.getMediaFileStreamEntity();
    }

}
