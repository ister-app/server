package app.ister.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * When a cluster-wide scheduled job last ran, keyed by job name. Written only through
 * {@link app.ister.core.repository.ScheduledJobRunRepository#tryClaim}; the entity exists so
 * {@code ddl-auto=validate} covers the table.
 */
@Entity
@Table(name = "scheduled_job_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduledJobRunEntity {

    @Id
    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private Instant lastRunAt;
}
