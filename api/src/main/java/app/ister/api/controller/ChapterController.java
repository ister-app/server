package app.ister.api.controller;

import app.ister.core.entity.BookEntity;
import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.PersonEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.WatchStatusRepository;
import app.ister.core.service.LibraryAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ChapterController {
    private final ChapterRepository chapterRepository;
    private final WatchStatusRepository watchStatusRepository;
    private final LibraryAccessService libraryAccessService;

    @PreAuthorize("hasRole('user')")
    @QueryMapping
    public Optional<ChapterEntity> chapterById(@Argument UUID id, Authentication authentication) {
        return chapterRepository.findById(id)
                .filter(chapter -> libraryAccessService.canAccess(
                        chapter.getBookEntity().getLibraryEntity(), authentication));
    }

    @SchemaMapping(typeName = "Chapter", field = "author")
    public PersonEntity author(ChapterEntity chapterEntity) {
        return chapterEntity.getPersonEntity();
    }

    @SchemaMapping(typeName = "Chapter", field = "book")
    public BookEntity book(ChapterEntity chapterEntity) {
        return chapterEntity.getBookEntity();
    }

    @SchemaMapping(typeName = "Chapter", field = "metadata")
    public List<MetadataEntity> metadata(ChapterEntity chapterEntity) {
        return chapterEntity.getMetadataEntities();
    }

    @SchemaMapping(typeName = "Chapter", field = "mediaFile")
    public List<MediaFileEntity> mediaFile(ChapterEntity chapterEntity) {
        return chapterEntity.getMediaFileEntities();
    }

    @BatchMapping(typeName = "Chapter", field = "watchStatus")
    public Map<ChapterEntity, List<WatchStatusEntity>> watchStatus(List<ChapterEntity> chapters, Authentication authentication) {
        Map<UUID, List<WatchStatusEntity>> byChapterId = watchStatusRepository
                .findByUserEntityExternalIdAndChapterEntityIn(authentication.getName(), chapters, Sort.by("dateUpdated").descending()).stream()
                .collect(Collectors.groupingBy(w -> w.getChapterEntity().getId()));
        return chapters.stream().collect(Collectors.toMap(c -> c, c -> byChapterId.getOrDefault(c.getId(), List.of())));
    }
}
