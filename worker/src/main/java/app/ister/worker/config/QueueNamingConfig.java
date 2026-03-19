package app.ister.worker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import static app.ister.core.MessageQueue.APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED;

@Configuration
public class QueueNamingConfig {

    @Value("${app.ister.server.name}")
    private String nodeName;

    public String getAnalyzeLibraryRequestedQueue() {
        return APP_ISTER_SERVER_ANALYZE_LIBRARY_REQUESTED + "." + nodeName;
    }
}
