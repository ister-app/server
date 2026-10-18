package app.ister.worker.events.gamefound;

import app.ister.core.entity.GameEntity;
import org.springframework.stereotype.Component;

/** No external game metadata source configured: titles and years come from the path and the files. */
@Component
public class NoopGameMetadataProvider implements GameMetadataProvider {

    @Override
    public boolean enrich(GameEntity game) {
        return false;
    }
}
