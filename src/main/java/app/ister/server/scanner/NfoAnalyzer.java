package app.ister.server.scanner;

import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.nfo.Parser;
import app.ister.server.repository.EpisodeRepository;
import app.ister.server.repository.MetadataRepository;
import app.ister.server.repository.SeasonRepository;
import app.ister.server.repository.ShowRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;

@Component
@Slf4j
public class NfoAnalyzer {
    @Autowired
    private MetadataRepository metadataRepository;
    @Autowired
    private ShowRepository showRepository;
    @Autowired
    private SeasonRepository seasonRepository;
    @Autowired
    private EpisodeRepository episodeRepository;

    public void analyze(DiskEntity diskEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.SHOW)) {
            analyzeShow(path, pathObject);
        } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeEpisode(path, pathObject);
        }
    }

    private void analyzeShow(String path, PathObject pathObject) {
        var show = showRepository.findByNameAndReleaseYear(pathObject.getShow(), pathObject.getShowYear());
        if (show.isPresent()) {
            try {
                var parsed = Parser.parseShow(path).orElseThrow();
                metadataRepository.save(MetadataEntity.builder()
                        .title(parsed.getTitle())
                        .description(parsed.getPlot())
                        .released(parsed.getPremiered())
                        .showEntity(show.get()).build());
            } catch (FileNotFoundException e) {
                log.error("Something went wrong when nfo parsing: {}", path);
            }
        }
    }

    private void analyzeEpisode(String path, PathObject pathObject) {
        var show = showRepository.findByNameAndReleaseYear(pathObject.getShow(), pathObject.getShowYear()).orElseThrow();
        var season = seasonRepository.findByShowEntityAndNumber(show, pathObject.getSeason()).orElseThrow();
        var episode = episodeRepository.findByShowEntityAndSeasonEntityAndNumber(show, season, pathObject.getEpisode());
        if (episode.isPresent()) {
            try {
                var parsed = Parser.parseEpisode(path).orElseThrow();
                metadataRepository.save(MetadataEntity.builder()
                        .title(parsed.getTitle())
                        .description(parsed.getPlot())
                        .released(parsed.getAired())
                        .episodeEntity(episode.get()).build());
            } catch (FileNotFoundException e) {
                log.error("Something went wrong when nfo parsing: {}", path);
            }
        }
    }
}
