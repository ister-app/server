package app.ister.api.controller;

import app.ister.core.entitiy.*;
import app.ister.core.repository.MovieRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

@Slf4j
@Controller
public class MovieController {
    @Autowired
    private MovieRepository movieRepository;

    @Autowired
    private WatchStatusRepository watchStatusRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<MovieEntity> moviesRecentAdded() {
        return movieRepository.findAll(Sort.by("dateCreated").descending());
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<MovieEntity> movieById(@Argument UUID id) {
        return movieRepository.findById(id);
    }

    @SchemaMapping(typeName = "Movie", field = "metadata")
    public List<MetadataEntity> metadata(MovieEntity movieEntity) {
        return movieEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Movie", field = "images")
    public List<ImageEntity> images(MovieEntity movieEntity) {
        return movieEntity.getImagesEntities();
    }

    @SchemaMapping(typeName = "Movie", field = "watchStatus")
    public List<WatchStatusEntity> watchStatus(MovieEntity movieEntity, Authentication authentication) {
        return watchStatusRepository.findByUserEntityExternalIdAndMovieEntity(authentication.getName(), movieEntity, Sort.by("dateUpdated").descending());
    }

    @SchemaMapping(typeName = "Movie", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(MovieEntity movieEntity) {
        return movieEntity.getMediaFileEntities();
    }

}
