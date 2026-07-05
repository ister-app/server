package app.ister.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Makes sure the collection and alias exist at startup so the first single-document upsert
 * doesn't 404. Typesense being unreachable must not fail boot; upserts will then be retried
 * and dead-lettered by the normal event flow until Typesense is back.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.ister.typesense", name = "enabled", havingValue = "true")
public class TypesenseInitializer implements ApplicationRunner {

    private final SearchIndexService searchIndexService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            searchIndexService.ensureCollection();
        } catch (Exception e) {
            log.warn("Typesense is unreachable at startup; the search collection will be created once it is available", e);
        }
    }
}
