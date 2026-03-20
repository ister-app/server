package app.ister.api.controller;

import app.ister.core.entity.*;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class EpisodeController {
    private final EpisodeRepository episodeRepository;
    private final WatchStatusRepository watchStatusRepository;

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

    @SchemaMapping(typeName = "MediaFile", field = "episodes")
    public List<EpisodeEntity> episodes(MediaFileEntity mediaFileEntity) {
        if (mediaFileEntity.getEpisodeEntity() == null) {
            return List.of();
        }
        return List.of(mediaFileEntity.getEpisodeEntity());
    }

}
