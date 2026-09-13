package app.ister.core.eventdata;

import app.ister.core.enums.EventType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class FileScanRequestedDataTest {

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    /** A message published by a pre-S3 node carries the path as a file: URI (Jackson's Path form). */
    @Test
    void acceptsTheLegacyFileUriForm() {
        FileScanRequestedData data = mapper.readValue(
                "{\"eventType\":\"FILE_SCAN_REQUESTED\",\"path\":\"file:///disk/Show%20(2024)/s01e01.mkv\",\"regularFile\":true,\"size\":5}",
                FileScanRequestedData.class);

        assertThat(data.getPath()).isEqualTo("/disk/Show (2024)/s01e01.mkv");
        assertThat(data.getEventType()).isEqualTo(EventType.FILE_SCAN_REQUESTED);
    }

    @Test
    void roundTripsPlainPathsAndS3Uris() {
        for (String path : new String[]{"/disk/a.mkv", "s3://bucket/media/a.mkv"}) {
            FileScanRequestedData data = FileScanRequestedData.builder()
                    .eventType(EventType.FILE_SCAN_REQUESTED).path(path).regularFile(true).size(1).build();
            FileScanRequestedData back = mapper.readValue(mapper.writeValueAsString(data), FileScanRequestedData.class);
            assertThat(back.getPath()).isEqualTo(path);
        }
    }
}
