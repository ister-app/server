package app.ister.server.service;

import app.ister.server.entitiy.EpisodeEntity;
import app.ister.server.entitiy.LibraryEntity;
import app.ister.server.entitiy.SeasonEntity;
import app.ister.server.entitiy.ShowEntity;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.SeasonRepository;
import app.ister.server.repository.ShowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class ScannerHelperService {
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private SeasonRepository seasonRepository;
    @Autowired
    private EpisodeRepository episodeRepository;
    @Autowired
    private ServerEventService serverEventService;


    /**
     * Check if the database contains a show wit the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public ShowEntity getOrCreateShow(LibraryEntity libraryEntity, String showName, int releaseYear) {
        Optional<ShowEntity> show = showRepository.findByNameAndReleaseYear(showName, releaseYear);
        if (show.isPresent()) {
            // The show exist
            return show.get();
        } else {
            // The show doesn't exist
            ShowEntity showEntity = ShowEntity.builder()
                    .libraryEntity(libraryEntity)
                    .name(showName)
                    .releaseYear(releaseYear).build();
            showRepository.save(showEntity);
            serverEventService.createShowFoundEvent(showEntity.getId());
            return showEntity;
        }
    }

    public SeasonEntity getOrCreateSeason(LibraryEntity libraryEntity, String showName, int releaseYear, int seasonNumber) {
        ShowEntity showEntity = getOrCreateShow(libraryEntity, showName, releaseYear);
        Optional<SeasonEntity> season = seasonRepository.findByShowEntityAndNumber(showEntity, seasonNumber);
        if (season.isPresent()) {
            return season.get();
        } else {
            SeasonEntity seasonEntity1 = SeasonEntity.builder()
                    .showEntity(showEntity)
                    .number(seasonNumber).build();
            seasonRepository.save(seasonEntity1);
            return seasonEntity1;
        }
    }

    /**
     * Check if the database contains an episode wit the given parameters.
     * - If it exists return it.
     * - Else create and return it.
     */
    public EpisodeEntity getOrCreateEpisode(LibraryEntity libraryEntity, String showName, int releaseYear, int seasonNumber, int episodeNumber) {
        ShowEntity showEntity = getOrCreateShow(libraryEntity, showName, releaseYear);
        SeasonEntity seasonEntity = getOrCreateSeason(libraryEntity, showName, releaseYear, seasonNumber);
        Optional<EpisodeEntity> episode = episodeRepository.findByShowEntityAndSeasonEntityAndNumber(showEntity, seasonEntity, episodeNumber);
        if (episode.isPresent()) {
            // The episode exist
            return episode.get();
        } else {
            // The episode doesn't exist
            EpisodeEntity episodeEntity1 = EpisodeEntity.builder()
                    .showEntity(showEntity)
                    .seasonEntity(seasonEntity)
                    .number(episodeNumber).build();
            episodeRepository.save(episodeEntity1);
            serverEventService.createEpisodeFoundEvent(episodeEntity1.getId());
            return episodeEntity1;
        }
    }
}
