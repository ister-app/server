package app.ister.api.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.io.IOException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RestExceptionHandlerTest {

    private final ExceptionHandlerMethodResolver resolver =
            new ExceptionHandlerMethodResolver(RestExceptionHandler.class);

    /**
     * A client aborting mid-stream surfaces as {@link AsyncRequestNotUsableException}, which is an
     * {@link IOException}. The specific handler must win over {@link RestExceptionHandler#handleIo}
     * so no {@link ProblemDetail} is written into an already-committed media response.
     */
    @Test
    void clientDisconnectResolvesToDedicatedVoidHandlerNotHandleIo() {
        Method resolved = resolver.resolveMethod(new AsyncRequestNotUsableException("aborted"));

        assertNotNull(resolved);
        assertEquals("handleClientDisconnect", resolved.getName());
        assertEquals(void.class, resolved.getReturnType());
    }

    @Test
    void plainIoErrorStillMapsToServiceUnavailableProblemDetail() {
        RestExceptionHandler subject = new RestExceptionHandler();

        ProblemDetail result = subject.handleIo(new IOException("segment timeout"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), result.getStatus());
    }

    @Test
    void plainIoErrorResolvesToHandleIo() {
        Method resolved = resolver.resolveMethod(new IOException("segment timeout"));

        assertNotNull(resolved);
        assertEquals("handleIo", resolved.getName());
    }
}
