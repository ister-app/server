package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.enums.ImageType;
import app.ister.server.repository.ImageRepository;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.scanner.enums.FileType;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ImageScanner implements Scanner {
    private final static List<String> BACKGROUND_FILE_NAMES = List.of("background", "thumb");
    private final static List<String> COVER_FILE_NAMES = List.of("cover");
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private ImageRepository imageRepository;

    @Override
    public boolean analyzable(Path path, Boolean isRegularFile, long size) {
        return isRegularFile && new PathObject(path.toString()).getFileType().equals(FileType.IMAGE);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, Boolean isRegularFile, long size) {
        if (imageRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString()).isEmpty()) {
            PathObject pathObject = new PathObject(path.toString());

            var imageEntity = ImageEntity.builder()
                    .directoryEntity(directoryEntity)
                    .sourceUri("file://" + path)
                    .path(path.toString());

            ImageType imageType = getImageType(path);
            if (imageType.equals(ImageType.UNKNOWN)) {
                return Optional.empty();
            }
            imageEntity.type(imageType);

            if (pathObject.getDirType().equals(DirType.SHOW)) {
                imageEntity.showEntity(scannerHelperService.getOrCreateShow(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear()));
            } else if (pathObject.getDirType().equals(DirType.SEASON)) {
                imageEntity.seasonEntity(scannerHelperService.getOrCreateSeason(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason()));
            } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
                imageEntity.episodeEntity(scannerHelperService.getOrCreateEpisode(directoryEntity.getLibraryEntity(), pathObject.getName(), pathObject.getYear(), pathObject.getSeason(), pathObject.getEpisode()));
            }

            ImageEntity build = imageEntity.build();
            imageRepository.save(build);
            return Optional.of(build);
        } else {
            return Optional.empty();
        }
    }

    private ImageType getImageType(Path path) {
        var filenameWithoutExt = removeExtension(path.getFileName().toString());
        if (BACKGROUND_FILE_NAMES.stream().anyMatch(filenameWithoutExt::contains)) {
            return ImageType.BACKGROUND;
        } else if (COVER_FILE_NAMES.stream().anyMatch(filenameWithoutExt::contains)) {
            return ImageType.COVER;
        } else {
            return ImageType.UNKNOWN;
        }
    }

    private String removeExtension(String string) {
        return string.replaceFirst("[.][^.]+$", "");
    }
}
