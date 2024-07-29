package app.ister.server.events.TMDBMetadata;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.entitiy.MovieEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.MetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MetadataSave {
    @Autowired
    private MetadataRepository metadataRepository;

    public void save(TMDBResult tmdbResult, MovieEntity movieEntity, ShowEntity showEntity, EpisodeEntity episodeEntity) {
        metadataRepository.save(MetadataEntity.builder()
                .movieEntity(movieEntity)
                .showEntity(showEntity)
                .episodeEntity(episodeEntity)
                .language(tmdbResult.getLanguage())
                .title(tmdbResult.getTitle())
                .released(tmdbResult.getReleased())
                .sourceUri(tmdbResult.getSourceUri())
                .description(tmdbResult.getDescription())
                .build());
    }
}
