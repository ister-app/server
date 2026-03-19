package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.repository.MetadataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetadataSave {
    private final MetadataRepository metadataRepository;

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
