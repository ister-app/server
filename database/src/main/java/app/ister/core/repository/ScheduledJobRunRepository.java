package app.ister.core.repository;

import app.ister.core.entity.ScheduledJobRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Duration;

public interface ScheduledJobRunRepository extends JpaRepository<ScheduledJobRunEntity, String> {

    /**
     * Moves the job's {@code last_run_at} to now, but only when the previous run is at least
     * {@code minIntervalSeconds} ago. Returns the number of rows written: 1 when this caller won
     * the run, 0 when another node already claimed it inside the interval. A single upsert, so
     * two nodes firing the same cron at the same second cannot both win. clock_timestamp() rather
     * than now(): the latter is frozen at transaction start, which would make a claim taken and
     * re-checked inside one transaction (the tests) compare equal instead of older.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO scheduled_job_run (name, last_run_at)
            VALUES (:name, clock_timestamp())
            ON CONFLICT (name) DO UPDATE SET last_run_at = clock_timestamp()
            WHERE scheduled_job_run.last_run_at < clock_timestamp() - make_interval(secs => :minIntervalSeconds)""",
            nativeQuery = true)
    int tryClaim(@Param("name") String name, @Param("minIntervalSeconds") long minIntervalSeconds);

    /** {@link #tryClaim(String, long)} with a {@link Duration}; must run inside a transaction. */
    default boolean tryClaim(String name, Duration minInterval) {
        return tryClaim(name, minInterval.toSeconds()) > 0;
    }
}
