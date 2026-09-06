package app.ister.core.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Helper-node configuration ({@code app.ister.helper.*}).
 *
 * <p>A node always serves the directories it owns. On top of that it can <em>help</em> other
 * nodes with the heavy {@link HelperJob}s for the directories listed in {@link #disks}: it then
 * subscribes to the same directory-scoped queues as the owner, and RabbitMQ shares the work
 * between them (competing consumers, prefetch 1). The source file is read over HTTP from the
 * owning node and results (subtitle files) are uploaded back to it.
 *
 * <p>An owner that wants to hand a job family off entirely lists it in {@link #offloadJobs}:
 * the queues for its own directories are still declared, but not consumed, so the messages
 * wait for a helper. Nothing is lost while no helper is up; the queue simply grows.
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.ister.helper")
public class HelperProperties {

    /** Directories (by {@code directory_entity.name}) owned by other nodes that this node helps with. */
    private final List<DiskEntry> disks = new ArrayList<>();

    /** Default job families for every helper disk without its own {@code jobs}. */
    private Set<HelperJob> jobs = EnumSet.allOf(HelperJob.class);

    /** Job families this node does <em>not</em> run for its own directories (owner-side opt-out). */
    private Set<HelperJob> offloadJobs = EnumSet.noneOf(HelperJob.class);

    /** Effective job set of a helper disk: its own list, or the node-wide default when empty. */
    public Set<HelperJob> jobsFor(DiskEntry disk) {
        return disk.getJobs() == null || disk.getJobs().isEmpty() ? jobs : disk.getJobs();
    }

    public boolean offloads(HelperJob job) {
        return offloadJobs != null && offloadJobs.contains(job);
    }

    @Getter
    @Setter
    public static class DiskEntry {
        private String name;
        private Set<HelperJob> jobs = EnumSet.noneOf(HelperJob.class);
    }
}
