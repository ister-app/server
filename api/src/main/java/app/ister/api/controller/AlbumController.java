package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.TrackEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.AlbumRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AlbumController {
    private final AlbumRepository albumRepository;
    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<AlbumEntity> albumById(@Argument UUID id) {
        return albumRepository.findById(id);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<AlbumEntity> albums(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> artistId,
            @Argument Optional<UUID> libraryId) {
        Pageable pageable = Paging.pageable(page, size, 20,
                sorting, SortingEnum.NAME, sortingOrder, SortingOrder.ASCENDING);
        if (artistId.isPresent()) {
            return personRepository.findById(artistId.get())
                    .map(artist -> albumRepository.findByPersonEntity(artist, pageable))
                    .orElseGet(() -> Page.empty(pageable));
        }
        if (libraryId.isPresent()) {
            return albumRepository.findByLibraryEntityId(libraryId.get(), pageable);
        }
        return albumRepository.findAll(pageable);
    }

    @SchemaMapping(typeName = "Album", field = "artist")
    public PersonEntity artist(AlbumEntity albumEntity) {
        return albumEntity.getPersonEntity();
    }

    @SchemaMapping(typeName = "Album", field = "tracks")
    public List<TrackEntity> tracks(AlbumEntity albumEntity) {
        return albumEntity.getTrackEntities();
    }

    @SchemaMapping(typeName = "Album", field = "metadata")
    public List<MetadataEntity> metadata(AlbumEntity albumEntity) {
        return albumEntity.getMetadataEntities();
    }

    @BatchMapping(typeName = "Album", field = "images")
    public Map<AlbumEntity, List<ImageEntity>> images(List<AlbumEntity> albums) {
        List<UUID> ids = albums.stream().map(AlbumEntity::getId).toList();
        Map<UUID, List<ImageEntity>> byAlbumId = imageRepository.findByAlbumEntityIdIn(ids).stream()
                .collect(Collectors.groupingBy(ImageEntity::getAlbumEntityId));
        return albums.stream().collect(Collectors.toMap(a -> a, a -> byAlbumId.getOrDefault(a.getId(), List.of())));
    }
}
