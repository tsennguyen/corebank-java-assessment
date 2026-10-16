package com.example.corebanking.recurring.application;

import com.example.corebanking.recurring.domain.RecurringProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job polling and executing due recurring transactions in bounded batches (L2-07, FR-17,
 * ADR-011).
 */
@Component
@EnableConfigurationProperties(RecurringProperties.class)
public class RecurringTransactionJob {

    private static final Logger log = LoggerFactory.getLogger(RecurringTransactionJob.class);

    private final RecurringExecutor recurringExecutor;
    private final RecurringProperties properties;

    public RecurringTransactionJob(
            RecurringExecutor recurringExecutor, RecurringProperties properties) {
        this.recurringExecutor = recurringExecutor;
        this.properties = properties;
    }

    /**
     * Triggered by Spring's {@link Scheduled} cron expression in the configured business timezone.
     * Skips automatic polling when {@code app.recurring.scheduler-enabled=false} (e.g., in
     * integration tests that invoke {@link #runDueTransactions()} deterministically).
     */
    @Scheduled(
            cron = "${app.recurring.cron:0 * * * * *}",
            zone = "${app.business-zone:Asia/Ho_Chi_Minh}")
    public void runScheduled() {
        if (!properties.isSchedulerEnabled()) {
            return;
        }
        runDueTransactions();
    }

    /**
     * Claims and executes up to {@code app.recurring.batch-size} due recurring transactions,
     * stopping early as soon as no more due {@code ACTIVE} instructions remain.
     *
     * @return number of due instructions processed in this batch
     */
    public int runDueTransactions() {
        int batchSize = Math.max(1, properties.getBatchSize());
        int processedCount = 0;

        for (int i = 0; i < batchSize; i++) {
            try {
                boolean claimed = recurringExecutor.executeNextDue();
                if (!claimed) {
                    break;
                }
                processedCount++;
            } catch (PessimisticLockingFailureException ex) {
                log.warn(
                        "Transient lock contention while executing due recurring transaction;"
                                + " will retry in next scheduled cycle: {}",
                        ex.getMessage());
                break;
            }
        }

        if (processedCount > 0) {
            log.info("RecurringTransactionJob completed batch [processed={}]", processedCount);
        }
        return processedCount;
    }
}
