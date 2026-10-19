package com.example.corebanking.common.scheduler;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Distributed cluster scheduler lock service backed by {@code core.shedlock} in PostgreSQL (Story
 * 6.2: NC-13, L2-07, ADR-011).
 *
 * <p>Provides job-level mutual exclusion ({@code lockAtMostFor}, {@code lockAtLeastFor}) across
 * multiple backend instances using an atomic {@code INSERT ... ON CONFLICT (name) DO UPDATE ...
 * WHERE core.shedlock.lock_until <= :now} statement committed in an independent transaction ({@code
 * REQUIRES_NEW}), complementing row-level {@code FOR UPDATE SKIP LOCKED} in {@code
 * RecurringExecutor}.
 */
@Service
public class SchedulerLockService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockService.class);

    private static final String ACQUIRE_LOCK_SQL =
            """
INSERT INTO core.shedlock (name, lock_until, locked_at, locked_by)
VALUES (:name, :lockUntil, :now, :lockedBy)
ON CONFLICT (name) DO UPDATE
    SET lock_until = EXCLUDED.lock_until,
        locked_at = EXCLUDED.locked_at,
        locked_by = EXCLUDED.locked_by
    WHERE core.shedlock.lock_until <= :now
""";

    private static final String RELEASE_LOCK_SQL =
            """
UPDATE core.shedlock
SET lock_until = :unlockAt
WHERE name = :name AND locked_by = :lockedBy
""";

    private final JdbcClient jdbcClient;
    private final Clock clock;
    private final TransactionTemplate requiresNewTx;
    private final String defaultInstanceId;

    public SchedulerLockService(
            JdbcClient jdbcClient, Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.defaultInstanceId = resolveDefaultInstanceId();
    }

    /**
     * Executes {@code task} only if the distributed lock {@code lockName} can be acquired in {@code
     * core.shedlock}. When another instance holds the lock, logs {@code skipped: lock held} and
     * returns {@code 0}.
     */
    public int executeWithLock(
            String lockName, Duration lockAtMostFor, Duration lockAtLeastFor, IntSupplier task) {
        return executeWithLock(lockName, lockAtMostFor, lockAtLeastFor, defaultInstanceId, task);
    }

    /**
     * Executes {@code task} under distributed lock {@code lockName} on behalf of {@code lockedBy}.
     *
     * @param lockName unique scheduled job lock name (e.g. {@code "recurringTransactionJob"})
     * @param lockAtMostFor maximum duration the lock is held if the node crashes
     * @param lockAtLeastFor minimum duration the lock remains held even if the job finishes early
     * @param lockedBy instance identifier claiming the lock
     * @param task scheduled batch callback returning processed item count
     * @return result of {@code task}, or {@code 0} when skipped because the lock is held
     */
    public int executeWithLock(
            String lockName,
            Duration lockAtMostFor,
            Duration lockAtLeastFor,
            String lockedBy,
            IntSupplier task) {
        Instant acquiredAt = clock.instant();
        boolean acquired = tryAcquire(lockName, acquiredAt, lockAtMostFor, lockedBy);
        if (!acquired) {
            log.info(
                    "Scheduler job '{}' skipped: lock held by another instance [caller={}]",
                    lockName,
                    lockedBy);
            return 0;
        }

        try {
            return task.getAsInt();
        } finally {
            releaseWithMinimumHold(lockName, acquiredAt, lockAtLeastFor, lockedBy);
        }
    }

    /**
     * Atomically attempts to acquire or steal an expired lock row in {@code core.shedlock} within a
     * {@code REQUIRES_NEW} transaction.
     */
    public boolean tryAcquire(
            String lockName, Instant now, Duration lockAtMostFor, String lockedBy) {
        Instant lockUntil = now.plus(lockAtMostFor);
        OffsetDateTime nowUtc = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime lockUntilUtc = OffsetDateTime.ofInstant(lockUntil, ZoneOffset.UTC);

        Integer updated =
                requiresNewTx.execute(
                        status ->
                                jdbcClient
                                        .sql(ACQUIRE_LOCK_SQL)
                                        .param("name", lockName)
                                        .param("lockUntil", lockUntilUtc)
                                        .param("now", nowUtc)
                                        .param("lockedBy", lockedBy)
                                        .update());
        return updated != null && updated > 0;
    }

    private void releaseWithMinimumHold(
            String lockName, Instant acquiredAt, Duration lockAtLeastFor, String lockedBy) {
        try {
            Instant finishedAt = clock.instant();
            Instant minUnlockAt = acquiredAt.plus(lockAtLeastFor);
            Instant effectiveUnlockAt = finishedAt.isAfter(minUnlockAt) ? finishedAt : minUnlockAt;
            OffsetDateTime unlockAtUtc =
                    OffsetDateTime.ofInstant(effectiveUnlockAt, ZoneOffset.UTC);

            requiresNewTx.executeWithoutResult(
                    status ->
                            jdbcClient
                                    .sql(RELEASE_LOCK_SQL)
                                    .param("unlockAt", unlockAtUtc)
                                    .param("name", lockName)
                                    .param("lockedBy", lockedBy)
                                    .update());
        } catch (RuntimeException ex) {
            log.warn(
                    "Failed to update lock_until on shedlock release [name={}, lockedBy={}]: {}",
                    lockName,
                    lockedBy,
                    ex.getMessage());
        }
    }

    private static String resolveDefaultInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception ex) {
            return "instance-" + UUID.randomUUID();
        }
    }
}
