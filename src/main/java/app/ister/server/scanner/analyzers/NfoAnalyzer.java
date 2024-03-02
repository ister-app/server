package app.ister.server.scanner.analyzers;

import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.MetadataEntity;
import app.ister.server.nfo.Parser;
import app.ister.server.repository.MetadataRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.service.ScannerHelperService;
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
    private ScannerHelperService scannerHelperService;

    public void analyze(DiskEntity diskEntity, String path) {
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.SHOW)) {
            analyzeShow(diskEntity, path, pathObject);
        } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeEpisode(diskEntity, path, pathObject);
        }
    }

    private void analyzeShow(DiskEntity diskEntity, String path, PathObject pathObject) {
        var show = scannerHelperService.getOrCreateShow(diskEntity.getCategorieEntity(), pathObject.getShow(), pathObject.getShowYear());
        try {
            var parsed = Parser.parseShow(path).orElseThrow();
            metadataRepository.save(MetadataEntity.builder()
                    .title(parsed.getTitle())
                    .description(parsed.getPlot())
                    .released(parsed.getPremiered())
                    .showEntity(show).build());
        } catch (FileNotFoundException e) {
            log.error("Something went wrong when nfo parsing: {}", path);
        }
    }

    private void analyzeEpisode(DiskEntity diskEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(diskEntity.getCategorieEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason(), pathObject.getEpisode());
        try {
            var parsed = Parser.parseEpisode(path).orElseThrow();
            metadataRepository.save(MetadataEntity.builder()
                    .title(parsed.getTitle())
                    .description(parsed.getPlot())
                    .released(parsed.getAired())
                    .episodeEntity(episode).build());
        } catch (FileNotFoundException e) {
            log.error("Something went wrong when nfo parsing: {}", path);
        }
    }
}
