package app.ister.server.scanner;

import app.ister.server.entitiy.*;
import app.ister.server.enums.ImageType;
import app.ister.server.repository.ImageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Optional;

@Component
@Slf4j
public class ImageAnalyzer implements Analyzer {
    final static String REGEX = "(.*)\\.(jpg|png)";

    @Autowired
    private ImageRepository imageRepository;
    @Autowired
    private MediaFileAnalyzer mediaFileAnalyzer;

    @Override
    public boolean analyzable(Path dir, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        return attrs.isRegularFile()
                && getMatcher(REGEX, dir.getFileName().toString().toLowerCase()).matches();
    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path file, BasicFileAttributes attrs, ArrayDeque<BaseEntity> analyzeStack) {
        ImageEntity imageEntity = new ImageEntity();
        imageEntity.setDiskEntity(diskEntity);
        imageEntity.setPath(file.toString());

        var filenameWithoutExt = removeExtension(file.getFileName().toString());
        ImageType imageType = switch (filenameWithoutExt) {
            case "background" -> ImageType.BACKGROUND;
            case "cover" -> ImageType.COVER;
            default -> ImageType.UNKNOWN;
        };
        if (imageType.equals(ImageType.UNKNOWN)) {
            return Optional.empty();
        }
        imageEntity.setType(imageType);

        BaseEntity parent = analyzeStack.peek();
        if (parent != null && parent.getClass().equals(ShowEntity.class)) {
            ShowEntity showEntity = (ShowEntity) parent;
            imageEntity.setShowEntity(showEntity);
        } else if (parent != null && parent.getClass().equals(SeasonEntity.class)) {
            SeasonEntity seasonEntity = (SeasonEntity) parent;
            imageEntity.setSeasonEntity(seasonEntity);
        } else {
            return Optional.empty();
        }

        imageRepository.save(imageEntity);
        return Optional.of(imageEntity);
    }

    private String removeExtension(String string) {
        return string.replaceFirst("[.][^.]+$", "");
    }
}
