package app.ister.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.listener.ListenerExecutionFailedException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryClassificationTest {

    @Test
    void transientFailuresAreRetried() {
        assertTrue(RabbitReliabilityConfig.isRetryable(new RuntimeException("connection reset")));
        // a unique-key race between two handlers resolves itself on the next attempt
        assertTrue(RabbitReliabilityConfig.isRetryable(new DataIntegrityViolationException("duplicate key",
                new org.hibernate.exception.ConstraintViolationException("dup", null, "ux_person"))));
    }

    @Test
    void deterministicFailuresAreNotRetriedEvenWhenWrapped() {
        Throwable nullColumn = new ListenerExecutionFailedException("listener",
                new DataIntegrityViolationException("not-null",
                        new org.hibernate.PropertyValueException("not-null property", "MediaFileStreamEntity", "codecName")));
        assertFalse(RabbitReliabilityConfig.isRetryable(nullColumn));

        Throwable badJson = new RuntimeException("feign", tools.jackson.databind.DatabindException
                .from((tools.jackson.core.JsonParser) null, "Numeric value (2334484620) out of range of int"));
        assertFalse(RabbitReliabilityConfig.isRetryable(badJson));

        assertFalse(RabbitReliabilityConfig.isRetryable(new IllegalArgumentException("wrong event type")));
    }

    @Test
    void selfReferencingCausesDoNotLoop() {
        RuntimeException loop = new RuntimeException("loop");
        loop.initCause(loop.getCause() == null ? new RuntimeException("inner") : loop);
        assertTrue(RabbitReliabilityConfig.isRetryable(loop));
    }
}
