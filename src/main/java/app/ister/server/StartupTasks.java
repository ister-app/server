package app.ister.server;

import app.ister.server.entitiy.CategorieEntity;
import app.ister.server.entitiy.DiskEntity;
import app.ister.server.entitiy.NodeEntity;
import app.ister.server.enums.DiskType;
import app.ister.server.repository.CatogorieRepository;
import app.ister.server.repository.DiskRepository;
import app.ister.server.repository.NodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupTasks {

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private DiskRepository diskRepository;

    @Autowired
    private CatogorieRepository catogorieRepository;

    @Value("${app.ister.server.library-dir}")
    private String libraryDir;

    @Value("${app.ister.server.cache-dir}")
    private String cacheDir;

    @EventListener(ContextRefreshedEvent.class)
    public void contextRefreshedEvent() {
        if (diskRepository.findByDiskType(DiskType.LIBRARY).isEmpty()) {
            CategorieEntity categorieEntity = new CategorieEntity();
            catogorieRepository.save(categorieEntity);

            NodeEntity nodeEntity = new NodeEntity("TestServer");
            nodeRepository.save(nodeEntity);

            DiskEntity diskEntity = new DiskEntity(nodeEntity, categorieEntity, libraryDir, DiskType.LIBRARY);
            diskRepository.save(diskEntity);

            diskRepository.save(new DiskEntity(nodeEntity, categorieEntity, cacheDir, DiskType.CACHE));
        }
    }
}
