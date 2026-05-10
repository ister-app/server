package app.ister.disk.events.nfofilefound;

import app.ister.core.entity.DirectoryEntity;
import app.ister.core.entity.MetadataEntity;
import app.ister.core.enums.EventType;
import app.ister.core.enums.LibraryType;
import app.ister.core.eventdata.NfoFileFoundData;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.MetadataRepository;
import app.ister.core.repository.OtherPathFileRepository;
import app.ister.core.service.ScannerHelperService;
import app.ister.core.Handle;
import app.ister.disk.nfo.Parser;
import app.ister.disk.scanner.MusicPathObject;
import app.ister.disk.scanner.PathObject;
import app.ister.disk.scanner.enums.DirType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileNotFoundException;

@Service
@Transactional
@Slf4j
@RequiredArgsConstructor
public class HandleNfoFileFound implements Handle<NfoFileFoundData> {
    private static final String FILE_URI_PREFIX = "file://";
    private static final String NFO_PARSE_ERROR = "Something went wrong when nfo parsing: {}";

    private final DirectoryRepository directoryRepository;
    private final MetadataRepository metadataRepository;
    private final OtherPathFileRepository otherPathFileRepository;
    private final ScannerHelperService scannerHelperService;

    @Override
    public EventType handles() {
        return EventType.NFO_FILE_FOUND;
    }

    @RabbitListener(queues = "#{@diskQueueNamingConfig.getNfoFileFoundQueues()}")
    @Override
    public void listener(app.ister.core.eventdata.NfoFileFoundData nfoFileFoundData) {
        Handle.super.listener(nfoFileFoundData);
    }

    @Override
    public Boolean handle(app.ister.core.eventdata.NfoFileFoundData nfoFileFoundData) {
        var directoryEntity = directoryRepository.findById(nfoFileFoundData.getDirectoryEntityUUID()).orElseThrow();
        analyze(directoryEntity, nfoFileFoundData.getPath());
        return true;
    }

    public void analyze(DirectoryEntity directoryEntity, String path) {
        if (directoryEntity.getLibraryEntity() != null
                && directoryEntity.getLibraryEntity().getLibraryType() == LibraryType.MUSIC) {
            analyzeMusicNfo(directoryEntity, path);
            return;
        }
        PathObject pathObject = new PathObject(path);
        if (pathObject.getDirType().equals(DirType.MOVIE)) {
            analyzeMovie(directoryEntity, path, pathObject);
        } else if (pathObject.getDirType().equals(DirType.SHOW)) {
            analyzeShow(directoryEntity, path, pathObject);
        } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
            analyzeEpisode(directoryEntity, path, pathObject);
        }
    }

    private void analyzeMusicNfo(DirectoryEntity directoryEntity, String path) {
        MusicPathObject musicPath = new MusicPathObject(directoryEntity.getPath(), path);
        if (musicPath.getDirType().equals(DirType.ARTIST)) {
            analyzeArtistNfo(directoryEntity, path, musicPath);
        } else if (musicPath.getDirType().equals(DirType.ALBUM)) {
            analyzeAlbumNfo(directoryEntity, path, musicPath);
        }
    }

    private void analyzeArtistNfo(DirectoryEntity directoryEntity, String path, MusicPathObject musicPath) {
        var artist = scannerHelperService.getOrCreateArtist(directoryEntity.getLibraryEntity(), musicPath.getArtistName());
        try {
            Parser.parseArtist(path).ifPresent(parsed -> {
                String title = parsed.getName() != null ? parsed.getName() : musicPath.getArtistName();
                var saved = metadataRepository.save(MetadataEntity.builder()
                        .title(title)
                        .description(parsed.getBiography())
                        .artistEntity(artist)
                        .sourceUri(FILE_URI_PREFIX + path).build());
                setMetadataFk(directoryEntity, path, saved);
            });
        } catch (java.io.FileNotFoundException _) {
            log.error(NFO_PARSE_ERROR, path);
        }
    }

    private void analyzeAlbumNfo(DirectoryEntity directoryEntity, String path, MusicPathObject musicPath) {
        var artist = scannerHelperService.getOrCreateArtist(directoryEntity.getLibraryEntity(), musicPath.getArtistName());
        var album = scannerHelperService.getOrCreateAlbum(directoryEntity.getLibraryEntity(), artist, musicPath.getAlbumName(), musicPath.getAlbumYear());
        try {
            Parser.parseAlbum(path).ifPresent(parsed -> {
                String title = parsed.getTitle() != null ? parsed.getTitle() : musicPath.getAlbumName();
                java.time.LocalDate released;
                if (parsed.getReleasedate() != null) {
                    released = parsed.getReleasedate();
                } else if (parsed.getYear() > 0) {
                    released = java.time.LocalDate.of(parsed.getYear(), 1, 1);
                } else {
                    released = null;
                }
                var saved = metadataRepository.save(MetadataEntity.builder()
                        .title(title)
                        .description(parsed.getReview())
                        .released(released)
                        .albumEntity(album)
                        .sourceUri(FILE_URI_PREFIX + path).build());
                setMetadataFk(directoryEntity, path, saved);
            });
        } catch (java.io.FileNotFoundException _) {
            log.error(NFO_PARSE_ERROR, path);
        }
    }

    private void analyzeMovie(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var movie = scannerHelperService.getOrCreateMovie(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear());
        try {
            Parser.parseMovie(path).ifPresent(parsed -> {
                var saved = metadataRepository.save(MetadataEntity.builder()
                        .title(parsed.getTitle())
                        .description(parsed.getPlot())
                        .released(parsed.getPremiered())
                        .movieEntity(movie)
                        .sourceUri(FILE_URI_PREFIX + path).build());
                setMetadataFk(directoryEntity, path, saved);
            });
        } catch (FileNotFoundException _) {
            log.error(NFO_PARSE_ERROR, path);
        }
    }

    private void analyzeShow(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var show = scannerHelperService.getOrCreateShow(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear());
        try {
            Parser.parseShow(path).ifPresent(parsed -> {
                var saved = metadataRepository.save(MetadataEntity.builder()
                        .title(parsed.getTitle())
                        .description(parsed.getPlot())
                        .released(parsed.getPremiered())
                        .showEntity(show)
                        .sourceUri(FILE_URI_PREFIX + path).build());
                setMetadataFk(directoryEntity, path, saved);
            });
        } catch (FileNotFoundException _) {
            log.error(NFO_PARSE_ERROR, path);
        }
    }

    private void analyzeEpisode(DirectoryEntity directoryEntity, String path, PathObject pathObject) {
        var episode = scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), pathObject.getEpisode());
        try {
            Parser.parseEpisode(path).ifPresent(parsed -> {
                var saved = metadataRepository.save(MetadataEntity.builder()
                        .title(parsed.getTitle())
                        .description(parsed.getPlot())
                        .released(parsed.getAired())
                        .episodeEntity(episode)
                        .sourceUri(FILE_URI_PREFIX + path).build());
                setMetadataFk(directoryEntity, path, saved);
            });
        } catch (FileNotFoundException _) {
            log.error(NFO_PARSE_ERROR, path);
        }
    }

    private void setMetadataFk(DirectoryEntity directoryEntity, String path, MetadataEntity saved) {
        otherPathFileRepository.findByDirectoryEntityAndPath(directoryEntity, path).ifPresent(f -> {
            f.setMetadataEntity(saved);
            otherPathFileRepository.save(f);
        });
    }
}
