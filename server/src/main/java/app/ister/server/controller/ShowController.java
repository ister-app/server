package app.ister.server.controller;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.ImageRepository;
import app.ister.server.repository.ShowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
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
public class ShowController {
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private EpisodeRepository episodeRepository;
    @Autowired
    private ImageRepository imageRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<ShowEntity> showById(@Argument UUID id) {
        return showRepository.findById(id);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public List<ShowEntity> showsRecentAdded() {
        return showRepository.findAll(Sort.by("dateCreated").descending());
    }

    @SchemaMapping(typeName = "Show", field = "episodes")
    public List<EpisodeEntity> episodes(ShowEntity showEntity) {
        return episodeRepository.findByShowEntityId(showEntity.getId(), Sort.by("number").ascending());
    }

    @SchemaMapping(typeName = "Show", field = "seasons")
    public List<SeasonEntity> seasons(ShowEntity showEntity) {
        return showEntity.getSeasonEntities();
    }

    @SchemaMapping(typeName = "Show", field = "metadata")
    public List<MetadataEntity> metadata(ShowEntity showEntity) {
        return showEntity.getMetadataEntities();
    }


    @SchemaMapping(typeName = "Show", field = "images")
    public List<ImageEntity> images(ShowEntity showEntity) {
        return imageRepository.findByShowEntityId(showEntity.getId());
    }

    @SchemaMapping(typeName = "Image", field = "show")
    public ShowEntity showForImage(ImageEntity imageEntity) {
        return imageEntity.getShowEntity();
    }
}
