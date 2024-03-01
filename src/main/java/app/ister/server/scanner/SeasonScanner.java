package app.ister.server.scanner;

import app.ister.server.entitiy.BaseEntity;
import app.ister.server.entitiy.DiskEntity;
import app.ister.server.service.ScannerHelperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

@Component
@Slf4j
public class SeasonScanner implements Scanner {
    @Autowired
    private ScannerHelperService scannerHelperService;

    @Override
    public boolean analyzable(Path dir, BasicFileAttributes attrs) {
        return attrs.isDirectory()
                && new PathObject(dir.toString()).getDirType().equals(DirType.SEASON);
    }

    @Override
    public Optional<BaseEntity> analyze(DiskEntity diskEntity, Path dir, BasicFileAttributes attrs) {
        PathObject pathObject = new PathObject(dir.toString());
        return Optional.ofNullable(scannerHelperService.getOrCreateSeason(diskEntity.getCategorieEntity(), pathObject.getShow(), pathObject.getShowYear(), pathObject.getSeason()));
    }
}
