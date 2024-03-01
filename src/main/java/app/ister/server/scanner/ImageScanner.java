package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.ImageEntity;
import app.ister.server.enums.ImageType;
import app.ister.server.repository.ImageRepository;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Optional;

@Component
@Slf4j
public class ImageScanner implements Scanner {
    @Autowired
    private ScannerHelperService scannerHelperService;
    @Autowired
    private ImageRepository imageRepository;

    private final static List<String>  BACKGROUND_FILE_NAMES = List.of("background", "thumb");
    private final static List<String>  COVER_FILE_NAMES = List.of("cover");

    @Override
    public boolean analyzable(Path path, BasicFileAttributes attrs) {
        return attrs.isRegularFile()
                && attrs.isRegularFile()
                && new PathObject(path.toString()).getFileType().equals(FileType.IMAGE);
    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path path, BasicFileAttributes attrs) {
        if (imageRepository.findByDiskEntityAndPath(diskEntity, path.toString()).isEmpty()) {
            PathObject pathObject = new PathObject(path.toString());

            var imageEntity = ImageEntity.builder()
                    .diskEntity(diskEntity)
                    .path(path.toString());

            ImageType imageType = getImageType(path);
            if (imageType.equals(ImageType.UNKNOWN)) {
                return Optional.empty();
            }
            imageEntity.type(imageType);

            if (pathObject.getDirType().equals(DirType.SHOW)) {
                imageEntity.showEntity(scannerHelperService.getOrCreateShow(diskEntity.getCategorieEntity(), pathObject.getShow(), pathObject.getShowYear()));
            } else if (pathObject.getDirType().equals(DirType.SEASON)) {
                imageEntity.seasonEntity(scannerHelperService.getOrCreateSeason(diskEntity.getCategorieEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason()));
            } else if (pathObject.getDirType().equals(DirType.EPISODE)) {
                imageEntity.episodeEntity(scannerHelperService.getOrCreateEpisode(diskEntity.getCategorieEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason(), pathObject.getEpisode()));
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
