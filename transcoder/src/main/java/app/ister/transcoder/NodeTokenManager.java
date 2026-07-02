package app.ister.transcoder;

import app.ister.core.service.StreamTokenService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NodeTokenManager {

    private final StreamTokenService streamTokenService;
    private volatile String downloadToken;
    private volatile String uploadToken;

    @PostConstruct
    public void init() {
        refresh();
    }

    // Refresh well before the 14h token TTL so a request never races token expiry;
    // the previous token stays valid for 2h after a refresh for in-flight transcodes.
    @Scheduled(fixedRate = 12 * 60 * 60 * 1000)
    public void refresh() {
        downloadToken = streamTokenService.createNodeDownloadToken().getToken().toString();
        uploadToken = streamTokenService.createNodeUploadToken().getToken().toString();
    }

    public String getDownloadToken() {
        return downloadToken;
    }

    public String getUploadToken() {
        return uploadToken;
    }
}
