package app.ister.disk.upload;

import app.ister.core.enums.UploadFileStatus;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.service.UserService;
import app.ister.core.status.NodeActivityRegistry;
import app.ister.core.storage.LibraryWriteStoreResolver;
import app.ister.disk.upload.UploadDtos.ChunkResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The HTTP contract a resuming client depends on. */
class LibraryUploadControllerTest {

    private final UploadSessionService sessionService = mock(UploadSessionService.class);
    private final UUID sessionId = UUID.randomUUID();
    private final UUID fileId = UUID.randomUUID();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new LibraryUploadController(sessionService,
                mock(DirectoryRepository.class), mock(LibraryWriteStoreResolver.class),
                mock(NodeActivityRegistry.class), mock(UserService.class))).build();
    }

    private String chunkUrl() {
        return "/library-upload/sessions/" + sessionId + "/files/" + fileId + "/chunk";
    }

    @Test
    void streamsAChunkWithItsOffsetAndLength() throws Exception {
        when(sessionService.chunk(eq(sessionId), eq(fileId), eq(16L), eq(5L), any()))
                .thenReturn(new ChunkResponse(fileId, 21, UploadFileStatus.UPLOADING));

        mockMvc.perform(post(chunkUrl()).param("offset", "16")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("hello".getBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedBytes").value(21));
    }

    @Test
    void anOffsetMismatchAnswersWithWhereToContinue() throws Exception {
        when(sessionService.chunk(any(), any(), eq(99L), eq(5L), any())).thenThrow(new UploadException(
                HttpStatus.CONFLICT, "File continues at 16", new ChunkResponse(fileId, 16, UploadFileStatus.UPLOADING)));

        mockMvc.perform(post(chunkUrl()).param("offset", "99")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("hello".getBytes()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.receivedBytes").value(16));
    }

    @Test
    void tooManyChunksAsksTheClientToBackOff() throws Exception {
        when(sessionService.chunk(any(), any(), eq(0L), eq(5L), any()))
                .thenThrow(new UploadException(HttpStatus.TOO_MANY_REQUESTS, "Too many chunks in flight"));

        mockMvc.perform(post(chunkUrl()).param("offset", "0")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content("hello".getBytes()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void everyEndpointIsAdminOnlyAndUsesOnlyGetAndPost() {
        PreAuthorize guard = LibraryUploadController.class.getAnnotation(PreAuthorize.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).isEqualTo("hasRole('admin')");

        // The web player calls this cross-origin and the CORS setup allows GET/HEAD/POST only:
        // a PUT or DELETE here would work from curl and fail in every browser.
        Method[] endpoints = Arrays.stream(LibraryUploadController.class.getDeclaredMethods())
                .filter(m -> Arrays.stream(m.getAnnotations())
                        .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping")))
                .toArray(Method[]::new);
        assertThat(endpoints).isNotEmpty().allSatisfy(m ->
                assertThat(m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class))
                        .as(m.getName()).isTrue());
    }
}
