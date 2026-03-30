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
    private volatile String currentToken;

    @PostConstruct
    public void init() {
        currentToken = streamTokenService.createNodeToken().getToken().toString();
    }

    @Scheduled(fixedRate = 14 * 60 * 60 * 1000)
    public void refresh() {
        currentToken = streamTokenService.createNodeToken().getToken().toString();
    }

    public String getToken() {
        return currentToken;
    }
}
