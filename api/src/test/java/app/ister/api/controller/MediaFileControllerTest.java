package app.ister.api.controller;

import app.ister.api.dto.MediaSegment;
import app.ister.core.entity.MediaFileEntity;
import app.ister.core.entity.MediaFileSegmentEntity;
import app.ister.core.enums.SegmentType;
import app.ister.core.repository.MediaFileSegmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MediaFileControllerTest {

    private final MediaFileSegmentRepository mediaFileSegmentRepository = mock(MediaFileSegmentRepository.class);
    private final MediaFileController subject = new MediaFileController(mediaFileSegmentRepository);

    @Test
    void formatIsTheUppercasedExtension() {
        assertEquals("CBZ", format("/comics/Naruto (1999)/Volume 1.cbz"));
        assertEquals("PDF", format("/comics/Asterix (1961)/Vol 3.PDF"));
        assertEquals("EPUB", format("/books/author/book.epub"));
        assertEquals("MKV", format("/shows/show/S01E01.mkv"));
    }

    @Test
    void formatIsNullWithoutAnExtension() {
        assertNull(format("/comics/series/volume"));
        assertNull(format("/comics/series/volume."));
        assertNull(format("/comics/ser.ies (1999)/volume"));
    }

    @Test
    void segmentsAreBatchedPerFileAndEmptyForUndetectedFiles() {
        MediaFileEntity detected = fileWithId();
        MediaFileEntity undetected = fileWithId();
        UUID episodeId = UUID.randomUUID();
        MediaFileSegmentEntity intro = MediaFileSegmentEntity.builder()
                .mediaFileEntityId(detected.getId())
                .episodeEntityId(episodeId)
                .type(SegmentType.INTRO)
                .startInMilliseconds(0)
                .endInMilliseconds(52_000)
                .build();
        ReflectionTestUtils.setField(intro, "id", UUID.randomUUID());
        when(mediaFileSegmentRepository.findByMediaFileEntityIdIn(any())).thenReturn(List.of(intro));

        Map<MediaFileEntity, List<MediaSegment>> result = subject.segments(List.of(detected, undetected));

        assertEquals(List.of(), result.get(undetected));
        MediaSegment segment = result.get(detected).getFirst();
        assertEquals(SegmentType.INTRO, segment.type());
        assertEquals(0, segment.startInMilliseconds());
        assertEquals(52_000, segment.endInMilliseconds());
        assertEquals(episodeId, segment.episodeId());
    }

    private MediaFileEntity fileWithId() {
        MediaFileEntity file = MediaFileEntity.builder().path("/x.mkv").size(1).build();
        ReflectionTestUtils.setField(file, "id", UUID.randomUUID());
        return file;
    }

    private String format(String path) {
        MediaFileEntity mediaFile = mock(MediaFileEntity.class);
        when(mediaFile.getPath()).thenReturn(path);
        return subject.format(mediaFile);
    }
}
