package com.example.corebanking.recurring.application;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.recurring.domain.RecurringProperties;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import com.example.corebanking.recurring.infrastructure.RecurringTransactionRepository;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.TransactionType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service that claims and executes a single due {@link RecurringTransaction}
 * instruction inside an isolated physical transaction ({@link Propagation#REQUIRES_NEW}) using
 * {@code FOR UPDATE SKIP LOCKED} (L2-07, FR-17, ADR-011).
 */
@Service
public class RecurringExecutor {

    private static final Logger log = LoggerFactory.getLogger(RecurringExecutor.class);

    private final RecurringTransactionRepository recurringRepository;
    private final TransactionService transactionService;
    private final RecurringProperties properties;
    private final Clock clock;

    public RecurringExecutor(
            RecurringTransactionRepository recurringRepository,
            TransactionService transactionService,
            RecurringProperties properties,
            Clock clock) {
        this.recurringRepository = recurringRepository;
        this.transactionService = transactionService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Claims the next due {@code ACTIVE} recurring transaction instruction using {@code FOR UPDATE
     * SKIP LOCKED} and executes it in an isolated transaction.
     *
     * <p>If a business rule fails (e.g., {@code INSUFFICIENT_BALANCE}, {@code LIMIT_EXCEEDED},
     * {@code ACCOUNT_NOT_ACTIVE}), the failure is recorded on the recurring instruction ({@code
     * failureCount++}, {@code lastError}, {@code nextRunAt} advanced to the next period, or
     * transitioned to {@code PAUSED} when {@code failureCount >= maxFailures}) and committed
     * cleanly without affecting other instructions in the batch.
     *
     * @return {@code true} if a due instruction was claimed and processed; {@code false} if no due
     *     instruction was available
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean executeNextDue() {
        Instant now = clock.instant();
        Optional<RecurringTransaction> nextOpt =
                recurringRepository.findNextDueForUpdateSkipLocked(now);
        if (nextOpt.isEmpty()) {
            return false;
        }

        RecurringTransaction recurring = nextOpt.get();
        LocalDate periodDate = recurring.getNextRunAt().atZone(clock.getZone()).toLocalDate();
        String referenceNo = formatPeriodReferenceNo(recurring.getId(), periodDate);
        TransactionType txType = recurring.getType().toTransactionType();

        try {
            transactionService.executeScheduledTransaction(
                    txType,
                    referenceNo,
                    recurring.getId(),
                    recurring.getSourceAccountId(),
                    recurring.getTargetAccountId(),
                    recurring.getAmount(),
                    recurring.getLocation(),
                    recurring.getDescription());

            recurring.recordSuccess(now, clock.getZone());
            recurringRepository.saveAndFlush(recurring);

            log.info(
                    "Recurring transaction executed successfully [recurringId={}, ref={},"
                            + " status={}, nextRunAt={}]",
                    recurring.getId(),
                    referenceNo,
                    recurring.getStatus(),
                    recurring.getNextRunAt());
        } catch (BusinessRuleException ex) {
            String errorMsg = ex.getErrorCode().name() + ": " + ex.getMessage();
            recurring.recordFailure(errorMsg, now, clock.getZone(), properties.getMaxFailures());
            recurringRepository.saveAndFlush(recurring);

            log.warn(
                    "Recurring transaction failed with business rule error [recurringId={}, ref={},"
                            + " failureCount={}, status={}, nextRunAt={}, error={}]",
                    recurring.getId(),
                    referenceNo,
                    recurring.getFailureCount(),
                    recurring.getStatus(),
                    recurring.getNextRunAt(),
                    errorMsg);
        } catch (ResourceNotFoundException ex) {
            String errorMsg = "RESOURCE_NOT_FOUND: " + ex.getMessage();
            recurring.recordFailure(errorMsg, now, clock.getZone(), properties.getMaxFailures());
            recurringRepository.saveAndFlush(recurring);

            log.warn(
                    "Recurring transaction failed with missing account [recurringId={}, ref={},"
                            + " failureCount={}, status={}, nextRunAt={}, error={}]",
                    recurring.getId(),
                    referenceNo,
                    recurring.getFailureCount(),
                    recurring.getStatus(),
                    recurring.getNextRunAt(),
                    errorMsg);
        }

        return true;
    }

    static String formatPeriodReferenceNo(Long recurringId, LocalDate periodDate) {
        return "REC-" + recurringId + "-" + periodDate.format(DateTimeFormatter.BASIC_ISO_DATE);
    }
}
