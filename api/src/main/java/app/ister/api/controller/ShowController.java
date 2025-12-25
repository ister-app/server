package app.ister.api.controller;

import app.ister.core.entitiy.*;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.EpisodeRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.ShowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    public Page<ShowEntity> shows(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder) {
        String sortingString = sorting.orElse(SortingEnum.NAME).getDatabaseString();
        Sort sortBy = Sort.by(sortingString);
        if (sortingOrder.isPresent()) {
            sortBy = sortingOrder.get() == SortingOrder.ASCENDING ? sortBy.ascending() : sortBy.descending();
        }
        Pageable pageable = PageRequest.of(page.orElse(0), size.orElse(10), sortBy);
        return showRepository.findAll(pageable);
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
