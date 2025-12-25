package app.ister.worker.scanner.scanners;

import app.ister.core.entitiy.BaseEntity;
import app.ister.core.entitiy.DirectoryEntity;
import app.ister.core.entitiy.ImageEntity;
import app.ister.core.enums.ImageType;
import app.ister.core.eventdata.ImageFoundData;
import app.ister.core.repository.ImageRepository;
import app.ister.core.service.MessageSender;
import app.ister.core.service.ScannerHelperService;
import app.ister.worker.scanner.PathObject;
import app.ister.worker.scanner.enums.DirType;
import app.ister.worker.scanner.enums.FileType;
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
    @Autowired
    private MessageSender messageSender;

    @Override
    public boolean analyzable(Path path, Boolean isRegularFile, long size) {
        return isRegularFile && new PathObject(path.toString()).getFileType().equals(FileType.IMAGE);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path path, Boolean isRegularFile, long size) {
        if (imageRepository.findByDirectoryEntityAndPath(directoryEntity, path.toString()).isEmpty()) {
            PathObject pathObject = new PathObject(path.toString());

            var imageEntity = ImageEntity.builder()
                    .directoryEntityId(directoryEntity.getId())
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
            messageSender.sendImageFound(ImageFoundData.fromImageEntity(build));
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
