package app.ister.server.controller;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MediaFileStreamEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.entitiy.UserEntity;
import app.ister.server.entitiy.WatchStatusEntity;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.WatchStatusRepository;
import app.ister.server.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Controller
public class EpisodeController {
    @Autowired
    private EpisodeRepository episodeRepository;

    @Autowired
    private WatchStatusRepository watchStatusRepository;

    @Autowired
    private UserService userService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<EpisodeEntity> episodesRecentWatched(Authentication authentication) {
        log.debug("Getting recent episode list for user: {}", authentication.getName());
        UserEntity userEntity = userService.getOrCreateUser(authentication);
        List<EpisodeEntity> result = new ArrayList<>();
        for (String[] strings : watchStatusRepository.findRecentEpisodesAndShowIdsByUserId(userEntity.getId())) {
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
        return watchStatusRepository.findByUserEntityExternalIdAndEpisodeEntity(authentication.getName(), episodeEntity, Sort.by("DateUpdated").descending());    }

    @SchemaMapping(typeName = "Episode", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(EpisodeEntity episodeEntity) {
        return episodeEntity.getMediaFileEntities();
    }

    @SchemaMapping(typeName = "MediaFile", field = "mediaFileStreams")
    public List<MediaFileStreamEntity> mediaFileStreams(MediaFileEntity mediaFileEntity) {
        return mediaFileEntity.getMediaFileStreamEntity();
    }

}
