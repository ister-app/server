package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ImageEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.SeriesEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class SeriesController {
    private final SeriesRepository seriesRepository;
    private final ImageRepository imageRepository;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<SeriesEntity> seriesById(@Argument UUID id) {
        return seriesRepository.findById(id);
    }

    @SchemaMapping(typeName = "Series", field = "author")
    public PersonEntity author(SeriesEntity seriesEntity) {
        return seriesEntity.getPersonEntity();
    }

    /** Series order comes from the entity's @OrderBy: seriesIndex ascending, unknown last. */
    @SchemaMapping(typeName = "Series", field = "books")
    public List<BookEntity> books(SeriesEntity seriesEntity) {
        return seriesEntity.getBookEntities();
    }

    /** A series has no artwork of its own; the first book's cover represents it. */
    @SchemaMapping(typeName = "Series", field = "cover")
    public ImageEntity cover(SeriesEntity seriesEntity) {
        return seriesEntity.getBookEntities().stream()
                .flatMap(book -> imageRepository.findByBookEntityId(book.getId()).stream())
                .filter(image -> image.getType() == ImageType.COVER)
                .findFirst()
                .orElse(null);
    }
}
