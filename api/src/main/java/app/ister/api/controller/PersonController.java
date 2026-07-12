package app.ister.api.controller;

import app.ister.core.entity.AlbumEntity;
import app.ister.core.entity.BookEntity;
import app.ister.core.entity.CreditEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.SortingEnum;
import app.ister.core.enums.SortingOrder;
import app.ister.core.repository.BookRepository;
import app.ister.core.repository.CreditRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.LibraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
public class PersonController {
    private final PersonRepository personRepository;
    private final ImageRepository imageRepository;
    private final LibraryRepository libraryRepository;
    private final CreditRepository creditRepository;
    private final BookRepository bookRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<PersonEntity> personById(@Argument UUID id) {
        return personRepository.findById(id);
    }

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Page<PersonEntity> persons(
            @Argument Optional<Integer> page,
            @Argument Optional<Integer> size,
            @Argument Optional<SortingEnum> sorting,
            @Argument Optional<SortingOrder> sortingOrder,
            @Argument Optional<UUID> libraryId) {
        Pageable pageable = Paging.pageable(page, size, 10,
                sorting, SortingEnum.NAME, sortingOrder, SortingOrder.ASCENDING);
        return libraryId.flatMap(libraryRepository::findById)
                .map(lib -> personRepository.findByLibraryEntity(lib, pageable))
                .orElseGet(() -> personRepository.findAll(pageable));
    }

    @SchemaMapping(typeName = "Person", field = "albums")
    public List<AlbumEntity> albums(PersonEntity personEntity) {
        return personEntity.getAlbumEntities();
    }

    @SchemaMapping(typeName = "Person", field = "books")
    public List<BookEntity> books(PersonEntity personEntity) {
        return bookRepository.findByPersonEntityId(personEntity.getId());
    }

    @SchemaMapping(typeName = "Person", field = "metadata")
    public List<MetadataEntity> metadata(PersonEntity personEntity) {
        return personEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Person", field = "credits")
    public List<CreditEntity> credits(PersonEntity personEntity) {
        return creditRepository.findByPersonEntityId(personEntity.getId(), Sort.by("castOrder"));
    }

    @BatchMapping(typeName = "Person", field = "images")
    public Map<PersonEntity, List<ImageEntity>> images(List<PersonEntity> persons) {
        List<UUID> ids = persons.stream().map(PersonEntity::getId).toList();
        Map<UUID, List<ImageEntity>> byPersonId = imageRepository.findByPersonEntityIdIn(ids).stream()
                .collect(Collectors.groupingBy(ImageEntity::getPersonEntityId));
        return persons.stream().collect(Collectors.toMap(a -> a, a -> byPersonId.getOrDefault(a.getId(), List.of())));
    }
}
