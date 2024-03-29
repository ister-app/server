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

            NodeEntity nodeEntity = NodeEntity.builder().name("TestServer").build();
            nodeRepository.save(nodeEntity);

            diskRepository.save(DiskEntity.builder()
                    .nodeEntity(nodeEntity)
                    .categorieEntity(categorieEntity)
                    .path(libraryDir)
                    .diskType(DiskType.LIBRARY).build());

            diskRepository.save(DiskEntity.builder()
                    .nodeEntity(nodeEntity)
                    .categorieEntity(categorieEntity)
                    .path(cacheDir)
                    .diskType(DiskType.CACHE).build());
        }
    }
}
