package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.ArtistEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.ArtistRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class ArtistController {
    private final ArtistRepository artistRepository;
    private final ImageRepository imageRepository;
    private final LibraryRepository libraryRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<ArtistEntity> artistById(@Argument UUID id) {
        return artistRepository.findById(id);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<ArtistEntity> artists(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId) {
        String sortingString = sorting.orElse(SortingEnum.NAME).getDatabaseString();
        Sort sortBy = Sort.by(sortingString);
        if (sortingOrder.isPresent()) {
            sortBy = sortingOrder.get() == SortingOrder.ASCENDING ? sortBy.ascending() : sortBy.descending();
        }
        Pageable pageable = PageRequest.of(page.orElse(0), size.orElse(10), sortBy);
        return libraryId.flatMap(libraryRepository::findById)
                .map(lib -> artistRepository.findByLibraryEntity(lib, pageable))
                .orElseGet(() -> artistRepository.findAll(pageable));
    }

    @SchemaMapping(typeName = "Artist", field = "albums")
    public List<AlbumEntity> albums(ArtistEntity artistEntity) {
        return artistEntity.getAlbumEntities();
    }

    @SchemaMapping(typeName = "Artist", field = "metadata")
    public List<MetadataEntity> metadata(ArtistEntity artistEntity) {
        return artistEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Artist", field = "images")
    public List<ImageEntity> images(ArtistEntity artistEntity) {
        return imageRepository.findByArtistEntityId(artistEntity.getId());
    }
}
