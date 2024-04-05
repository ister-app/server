package app.ister.server.scanner.scanners;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DirectoryEntity;
import app.ister.server.scanner.PathObject;
import app.ister.server.scanner.enums.DirType;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

@Component
@Slf4j
public class ShowScanner implements Scanner {
    @Autowired
    private ScannerHelperService scannerHelperService;

    @Override
    public boolean analyzable(Path dir, BasicFileAttributes attrs) {
        return attrs.isDirectory()
                && new PathObject(dir.toString()).getDirType().equals(DirType.SHOW);
    }

    @Override
    public Optional<BaseEntity> analyze(DirectoryEntity directoryEntity, Path dir, BasicFileAttributes attrs) {
        PathObject pathObject = new PathObject(dir.toString());
        return Optional.ofNullable(scannerHelperService.getOrCreateShow(directoryEntity.getLibraryEntity(), pathObject.getShow(), pathObject.getShowYear()));
    }
}
