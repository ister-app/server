package app.ister.core.service;

import app.ister.core.entity.ChapterEntity;
import app.ister.core.entity.UserEntity;
import app.ister.core.entity.WatchStatusEntity;
import app.ister.core.repository.ChapterRepository;
import app.ister.core.repository.WatchStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where a listener should continue an audiobook: the chapter they last played, or the next
 * unfinished one when they finished it. Chapters they skipped earlier in the book are left
 * behind — continuing means continuing, not going back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookResumeService {
    private final ChapterRepository chapterRepository;
    private final WatchStatusRepository watchStatusRepository;

    /** The chapter to resume at, plus when the user last listened to this book. */
    public record ChapterResume(ChapterEntity chapter, Instant lastPlayed) {
    }

    /**
     * Empty when the book has no chapters, was never started, or was played through to the end;
     * callers then start at the first chapter (or leave the book out of a continue-listening list).
     */
    @Transactional(readOnly = true)
    public Optional<ChapterResume> resume(UserEntity userEntity, UUID bookId) {
        List<ChapterEntity> chapters = chapterRepository.findByBookEntity_Id(bookId, Sort.by("number").ascending());
        if (chapters.isEmpty()) {
            return Optional.empty();
        }

        List<WatchStatusEntity> statuses = watchStatusRepository.findByUserEntityExternalIdAndChapterEntityIn(
                userEntity.getExternalId(), chapters, Sort.by("dateUpdated").descending());
        if (statuses.isEmpty()) {
            return Optional.empty();
        }
        Map<UUID, WatchStatusEntity> statusByChapterId = statuses.stream()
                .collect(Collectors.toMap(status -> status.getChapterEntity().getId(), Function.identity(),
                        (mostRecent, older) -> mostRecent));

        WatchStatusEntity lastPlayed = statuses.getFirst();
        int index = indexOfChapter(chapters, lastPlayed.getChapterEntity().getId());
        if (index < 0) {
            return Optional.empty();
        }
        for (ChapterEntity chapter : chapters.subList(index, chapters.size())) {
            WatchStatusEntity status = statusByChapterId.get(chapter.getId());
            if (status == null || !status.isWatched()) {
                return Optional.of(new ChapterResume(chapter, lastPlayed.getDateUpdated()));
            }
        }
        return Optional.empty();
    }

    private int indexOfChapter(List<ChapterEntity> chapters, UUID chapterId) {
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getId().equals(chapterId)) {
                return i;
            }
        }
        return -1;
    }
}
