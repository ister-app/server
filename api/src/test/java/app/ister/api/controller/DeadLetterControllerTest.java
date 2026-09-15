package app.ister.api.controller;

import app.ister.core.service.DeadLetterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeadLetterControllerTest {
    @InjectMocks
    private DeadLetterController subject;
    @Mock
    private DeadLetterService deadLetterService;

    @Test
    void countAndReplayDelegate() {
        when(deadLetterService.count()).thenReturn(13);
        when(deadLetterService.replay()).thenReturn(12);
        assertEquals(13, subject.deadLetterCount());
        assertEquals(12, subject.replayDeadLetters());
    }
}
