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

import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class MetadataSave {
    private final MetadataRepository metadataRepository;
    private final ServerEventService serverEventService;

    public void save(TMDBResult tmdbResult, MovieEntity movieEntity, ShowEntity showEntity, EpisodeEntity episodeEntity) {
        // Delete-then-insert so a re-fetch (e.g. after correcting a wrong TMDB match) overwrites the
        // existing row for this language instead of piling up duplicate metadata rows.
        removeExistingForLanguage(tmdbResult.getLanguage(), movieEntity, showEntity, episodeEntity);
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

    private void removeExistingForLanguage(String language, MovieEntity movieEntity, ShowEntity showEntity, EpisodeEntity episodeEntity) {
        List<MetadataEntity> existing;
        if (movieEntity != null) {
            existing = metadataRepository.findByMovieEntityId(movieEntity.getId());
        } else if (showEntity != null) {
            existing = metadataRepository.findByShowEntityId(showEntity.getId());
        } else if (episodeEntity != null) {
            existing = metadataRepository.findByEpisodeEntityId(episodeEntity.getId());
        } else {
            return;
        }
        List<MetadataEntity> sameLanguage = existing.stream()
                .filter(m -> Objects.equals(m.getLanguage(), language))
                .toList();
        if (!sameLanguage.isEmpty()) {
            metadataRepository.deleteAll(sameLanguage);
        }
    }
}
