package app.ister.server.controller;

import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MediaFileEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.entitiy.WatchStatusEntity;
import app.ister.server.repository.MovieRepository;
import app.ister.server.repository.WatchStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;

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
        return watchStatusRepository.findByUserEntityExternalIdAndMovieEntity(authentication.getName(), movieEntity, Sort.by("DateUpdated").descending());
    }

    @SchemaMapping(typeName = "Movie", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(MovieEntity movieEntity) {
        return movieEntity.getMediaFileEntities();
    }

}
