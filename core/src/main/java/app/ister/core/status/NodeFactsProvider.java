package app.ister.core.status;

import app.ister.core.config.HelperJob;
import app.ister.core.config.HelperProperties;
import app.ister.core.entity.DirectoryEntity;
import app.ister.core.eventdata.NodeActivityStatusData.DirectoryFact;
import app.ister.core.eventdata.NodeActivityStatusData.HelperDiskFact;
import app.ister.core.eventdata.NodeActivityStatusData.NodeFacts;
import app.ister.core.repository.DirectoryRepository;
import app.ister.core.repository.NodeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Assembles the {@link NodeFacts} this node publishes with its activity snapshots: host,
 * uptime, the directories it owns with their disk space, and its helper configuration.
 * Only the node itself can stat its own mounts, which is why this travels on the status
 * exchange instead of being resolved by the API node. Cached for {@link #TTL_MS} so a
 * scan burst that republishes every 2s does not stat every mount every 2s.
 */
@Slf4j
@Component
public class NodeFactsProvider {

    static final long TTL_MS = 30_000;

    private final DirectoryRepository directoryRepository;
    private final NodeRepository nodeRepository;
    private final HelperProperties helperProperties;
    private final String nodeName;
    private final Instant startedAt = Instant.now();
    private final String hostname = resolveHostname();

    private NodeFacts cached;
    private long cachedAtMillis;

    public NodeFactsProvider(DirectoryRepository directoryRepository, NodeRepository nodeRepository,
                             HelperProperties helperProperties,
                             @Value("${app.ister.server.name}") String nodeName) {
        this.directoryRepository = directoryRepository;
        this.nodeRepository = nodeRepository;
        this.helperProperties = helperProperties;
        this.nodeName = nodeName;
    }

    public synchronized NodeFacts facts() {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAtMillis < TTL_MS) {
            return cached;
        }
        try {
            cached = build();
            cachedAtMillis = now;
        } catch (RuntimeException e) {
            // The activity heartbeat must never die on a facts lookup (database hiccup at
            // startup); keep whatever was published last.
            log.debug("Could not refresh node facts", e);
        }
        return cached;
    }

    private NodeFacts build() {
        List<DirectoryFact> directories = nodeRepository.findByName(nodeName)
                .map(node -> directoryRepository.findByNodeEntity(node).stream()
                        .sorted(Comparator.comparing(DirectoryEntity::getName))
                        .map(NodeFactsProvider::directoryFact)
                        .toList())
                .orElse(List.of());
        List<HelperDiskFact> helperDisks = helperProperties.getDisks().stream()
                .map(disk -> new HelperDiskFact(disk.getName(),
                        helperProperties.jobsFor(disk).stream().map(HelperJob::name).sorted().toList()))
                .toList();
        List<String> offloadJobs = helperProperties.getOffloadJobs() == null ? List.of()
                : helperProperties.getOffloadJobs().stream().map(HelperJob::name).sorted().toList();
        Runtime runtime = Runtime.getRuntime();
        return new NodeFacts(hostname, startedAt, System.getProperty("java.version"),
                runtime.availableProcessors(), runtime.maxMemory(), directories, helperDisks, offloadJobs);
    }

    static DirectoryFact directoryFact(DirectoryEntity directory) {
        Long total = null;
        Long free = null;
        try {
            Path path = Path.of(directory.getPath());
            if (Files.exists(path)) {
                FileStore store = Files.getFileStore(path);
                total = store.getTotalSpace();
                free = store.getUsableSpace();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Cannot stat {}", directory.getPath(), e);
        }
        String library = directory.getLibraryEntity() == null ? null : directory.getLibraryEntity().getName();
        return new DirectoryFact(directory.getName(), directory.getPath(),
                directory.getDirectoryType() == null ? null : directory.getDirectoryType().name(),
                library, total, free);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException _) {
            return null;
        }
    }
}
