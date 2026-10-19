package com.example.corebanking.common.idempotency;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Daily scheduled job cleaning up expired idempotency keys (older than configured TTL). */
@Component
@ConditionalOnProperty(
        name = "app.idempotency.cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IdempotencyCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupScheduler.class);

    private final IdempotencyRepository idempotencyRepository;
    private final Clock clock;

    public IdempotencyCleanupScheduler(IdempotencyRepository idempotencyRepository, Clock clock) {
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
    }

    /** Runs every day at 03:00 AM (default) in business timezone to purge expired keys. */
    @Scheduled(
            cron = "${app.idempotency.cleanup-cron:0 0 3 * * *}",
            zone = "${app.business-zone:Asia/Ho_Chi_Minh}")
    @Transactional
    public void cleanupExpiredKeys() {
        int deleted = idempotencyRepository.deleteExpiredKeys(clock.instant());
        log.info("Idempotency keys cleanup completed: {} expired key(s) deleted", deleted);
    }
}
