package app.ister.core.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Builds the directory-scoped queue names ({@code <base>.<directoryName>}) a node consumes.
 *
 * <p>Two shapes exist:
 * <ul>
 *   <li>{@link #queues(String)} — plain disk events: the node's own directories plus its cache
 *       directory. These always run on the owner because they need the file on local disk.</li>
 *   <li>{@link #queues(String, HelperJob)} — the heavy job families: the same set, minus the own
 *       directories when the owner offloads that job, plus every helper disk configured for it.
 *       A helper node reads the source over HTTP, so it can serve directories it does not own.</li>
 * </ul>
 * The cache directory is always included, offloaded or not: podcast downloads live there and no
 * other node declares that name, so nobody else could pick those messages up.
 */
@Slf4j
@Component("directoryQueueNames")
public class DirectoryQueueNames {

    private final OwnDirectoriesProperties ownDirectories;
    private final HelperProperties helperProperties;
    private final TranscoderDisksConfig legacyTranscoderDisks;
    private final String nodeName;
    public DirectoryQueueNames(OwnDirectoriesProperties ownDirectories,
                               HelperProperties helperProperties,
                               TranscoderDisksConfig legacyTranscoderDisks,
                               @Value("${app.ister.server.name}") String nodeName) {
        this.ownDirectories = ownDirectories;
        this.helperProperties = helperProperties;
        this.legacyTranscoderDisks = legacyTranscoderDisks;
        this.nodeName = nodeName;
    }

    @PostConstruct
    void logConfiguration() {
        if (usesLegacyTranscoderDisks()) {
            log.warn("app.ister.transcoder.disks is deprecated; use app.ister.helper.disks[n].name with "
                    + "app.ister.helper.jobs=TRANSCODE instead. Treating {} as helper disks for TRANSCODE only.",
                    legacyDiskNames());
        }
        Map<HelperJob, List<String>> perJob = new EnumMap<>(HelperJob.class);
        for (HelperJob job : HelperJob.values()) {
            List<String> names = helperDirectoryNames(job);
            if (!names.isEmpty()) {
                perJob.put(job, names);
            }
        }
        if (!perJob.isEmpty()) {
            log.info("This node helps other nodes with: {}", perJob);
        }
        if (!helperProperties.getOffloadJobs().isEmpty()) {
            log.info("This node offloads {} for its own directories {}; those queues are only consumed by helpers",
                    helperProperties.getOffloadJobs(), ownDirectoryNames());
        }
    }

    /**
     * Media files without a library directory (podcast downloads) live in this node's cache
     * directory and their MediaFileEntity points at it, so events for them are routed to a
     * cache-directory-scoped queue. Without it those events end up on a queue nobody consumes.
     */
    public String cacheDirName() {
        return nodeName + "-cache-directory";
    }

    public String nodeName() {
        return nodeName;
    }

    public List<String> ownDirectoryNames() {
        return ownDirectories.names();
    }

    /** Directories owned by other nodes that this node helps with for {@code job}. */
    public List<String> helperDirectoryNames(HelperJob job) {
        Set<String> names = new LinkedHashSet<>();
        helperProperties.getDisks().stream()
                .filter(disk -> helperProperties.jobsFor(disk).contains(job))
                .map(HelperProperties.DiskEntry::getName)
                .forEach(names::add);
        if (job == HelperJob.TRANSCODE && usesLegacyTranscoderDisks()) {
            names.addAll(legacyDiskNames());
        }
        return List.copyOf(names);
    }

    /** All helper disk names regardless of job, for startup validation. */
    public List<String> allHelperDirectoryNames() {
        Set<String> names = new LinkedHashSet<>();
        Stream.of(HelperJob.values()).forEach(job -> names.addAll(helperDirectoryNames(job)));
        return List.copyOf(names);
    }

    /**
     * Directory suffixes this node consumes for {@code job}: own directories (unless the job is
     * offloaded — or, for backward compatibility, unless the legacy transcoder disks list is
     * set, which used to <em>replace</em> the own directories), the helper disks for the job,
     * and always the cache directory.
     */
    public List<String> namesFor(HelperJob job) {
        Set<String> names = new LinkedHashSet<>();
        boolean legacyReplacesOwn = job == HelperJob.TRANSCODE && usesLegacyTranscoderDisks();
        if (!helperProperties.offloads(job) && !legacyReplacesOwn) {
            names.addAll(ownDirectoryNames());
        }
        names.addAll(helperDirectoryNames(job));
        names.add(cacheDirName());
        return List.copyOf(names);
    }

    /** Queue names for a plain (owner-only) directory-scoped event: own directories + cache dir. */
    public String[] queues(String base) {
        return Stream.concat(ownDirectoryNames().stream(), Stream.of(cacheDirName()))
                .distinct()
                .map(name -> base + "." + name)
                .toArray(String[]::new);
    }

    /** Queue names for a helper-capable event family. */
    public String[] queues(String base, HelperJob job) {
        return namesFor(job).stream()
                .map(name -> base + "." + name)
                .toArray(String[]::new);
    }

    private boolean usesLegacyTranscoderDisks() {
        return legacyTranscoderDisks != null && !legacyTranscoderDisks.getDisks().isEmpty();
    }

    private List<String> legacyDiskNames() {
        return legacyTranscoderDisks.getDisks().stream().map(TranscoderDisksConfig.DiskEntry::getName).toList();
    }
}
