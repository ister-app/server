package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.entity.ShowEntity;
import app.ister.core.enums.SearchEntityType;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.service.ServerEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetadataSave {
    private final MetadataRepository metadataRepository;
    private final ServerEventService serverEventService;

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
        if (movieEntity != null) {
            serverEventService.createSearchIndexEvent(SearchEntityType.MOVIE, movieEntity.getId());
        } else if (showEntity != null) {
            serverEventService.createSearchIndexEvent(SearchEntityType.SHOW, showEntity.getId());
        } else if (episodeEntity != null) {
            serverEventService.createSearchIndexEvent(SearchEntityType.EPISODE, episodeEntity.getId());
        }
    }
}
