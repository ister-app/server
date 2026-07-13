package app.ister.transcoder.cleanup;

import app.ister.core.repository.MediaFileRepository;
import app.ister.transcoder.HlsTranscodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmpTranscodeCleanupSchedulerTest {

    private static final String TMP_DIR = "/tmp/ister";

    @Mock
    private MediaFileRepository mediaFileRepository;

    @Mock
    private HlsTranscodeService transcodeService;

    @Mock
    private TmpCleanupService tmpCleanupService;

    @InjectMocks
    private TmpTranscodeCleanupScheduler subject;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(subject, "tmpDir", TMP_DIR);
        ReflectionTestUtils.setField(subject, "enabled", true);
        ReflectionTestUtils.setField(subject, "dryRun", true);
        ReflectionTestUtils.setField(subject, "minAge", Duration.ofHours(24));
    }

    @Test
    void runDoesNothingWhenDisabled() {
        ReflectionTestUtils.setField(subject, "enabled", false);

        subject.run();

        verifyNoInteractions(tmpCleanupService, mediaFileRepository, transcodeService);
    }

    @Test
    void runDelegatesToCleanupServiceInDryRun() throws IOException {
        when(tmpCleanupService.clean(eq(Path.of(TMP_DIR)), any(), any(), any(Clock.class),
                eq(Duration.ofHours(24)), eq(true)))
                .thenReturn(new TmpCleanupService.CleanupResult(2, 2048, 1));

        subject.run();

        verify(tmpCleanupService).clean(eq(Path.of(TMP_DIR)), any(), any(), any(Clock.class),
                eq(Duration.ofHours(24)), eq(true));
    }

    @Test
    void runPassesLiveFlagWhenDryRunDisabled() throws IOException {
        ReflectionTestUtils.setField(subject, "dryRun", false);
        when(tmpCleanupService.clean(any(), any(), any(), any(Clock.class), any(), eq(false)))
                .thenReturn(new TmpCleanupService.CleanupResult(0, 0, 0));

        subject.run();

        verify(tmpCleanupService).clean(any(), any(), any(), any(Clock.class), any(), eq(false));
    }

    @Test
    void runPassesRepositoryAndPassStatePredicates() throws IOException {
        UUID mediaFileId = UUID.randomUUID();
        when(mediaFileRepository.existsById(mediaFileId)).thenReturn(true);
        when(transcodeService.hasActivePassForFile(mediaFileId)).thenReturn(false);
        when(tmpCleanupService.clean(any(), any(), any(), any(Clock.class), any(), anyBoolean()))
                .thenAnswer(invocation -> {
                    Predicate<UUID> exists = invocation.getArgument(1);
                    Predicate<UUID> hasActivePass = invocation.getArgument(2);
                    assertTrue(exists.test(mediaFileId));
                    assertFalse(hasActivePass.test(mediaFileId));
                    return new TmpCleanupService.CleanupResult(0, 0, 1);
                });

        subject.run();

        verify(mediaFileRepository).existsById(mediaFileId);
        verify(transcodeService).hasActivePassForFile(mediaFileId);
    }

    @Test
    void runSwallowsIoException() throws IOException {
        when(tmpCleanupService.clean(any(), any(), any(), any(Clock.class), any(), anyBoolean()))
                .thenThrow(new IOException("disk gone"));

        assertDoesNotThrow(() -> subject.run());
    }
}
