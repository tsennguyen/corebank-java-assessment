package com.example.corebanking.recurring.domain;

import com.example.corebanking.common.domain.BaseEntity;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Rich domain entity representing a scheduled recurring transaction instruction ({@code DEPOSIT},
 * {@code PAYMENT}, or {@code TRANSFER}) with lifecycle state machine and anchor-day schedule
 * calculation (L2-06, L2-07, FR-16, FR-17).
 */
@Entity
@Table(name = "recurring_transaction", schema = "core")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecurringTransaction extends BaseEntity {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private RecurringTransactionType type;

    @Column(name = "source_account_id", updatable = false)
    private Long sourceAccountId;

    @Column(name = "target_account_id", updatable = false)
    private Long targetAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20, updatable = false)
    private RecurringFrequency frequency;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date", updatable = false)
    private LocalDate endDate;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RecurringStatus status;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    private RecurringTransaction(
            RecurringTransactionType type,
            Long sourceAccountId,
            Long targetAccountId,
            BigDecimal amount,
            String location,
            String description,
            RecurringFrequency frequency,
            LocalDate startDate,
            LocalDate endDate,
            Instant nextRunAt,
            String createdBy) {
        this.type = type;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.location = location;
        this.description = description;
        this.frequency = frequency;
        this.startDate = startDate;
        this.endDate = endDate;
        this.nextRunAt = nextRunAt;
        this.status = RecurringStatus.ACTIVE;
        this.failureCount = 0;
        this.createdBy = createdBy;
    }

    /**
     * Creates and validates a new {@code ACTIVE} recurring transaction instruction.
     *
     * @param type recurring transaction type ({@code DEPOSIT}, {@code PAYMENT}, {@code TRANSFER})
     * @param sourceAccountId source account ID (required for {@code PAYMENT} and {@code TRANSFER})
     * @param targetAccountId target account ID (required for {@code DEPOSIT} and {@code TRANSFER})
     * @param amount positive transaction amount
     * @param frequency execution frequency ({@code DAILY}, {@code WEEKLY}, {@code MONTHLY})
     * @param startDate first scheduled date (must be {@code >= today} in business zone)
     * @param endDate optional last scheduled date (must be {@code >= startDate} if present)
     * @param location optional location metadata
     * @param description optional description metadata
     * @param createdBy actor username
     * @param clock application business clock
     * @return validated {@link RecurringTransaction} instance in {@code ACTIVE} state
     */
    public static RecurringTransaction create(
            RecurringTransactionType type,
            Long sourceAccountId,
            Long targetAccountId,
            BigDecimal amount,
            RecurringFrequency frequency,
            LocalDate startDate,
            LocalDate endDate,
            String location,
            String description,
            String createdBy,
            Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (type == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Recurring transaction type is required");
        }
        if (frequency == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Recurring frequency is required");
        }
        if (startDate == null) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Start date is required");
        }
        if (amount == null) {
            throw new BusinessRuleException(ErrorCode.VALIDATION_FAILED, "Amount is required");
        }

        Money validatedMoney = Money.of(amount);
        if (!validatedMoney.isPositive()) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Recurring transaction amount must be greater than zero");
        }
        validateAccountsByType(type, sourceAccountId, targetAccountId);

        LocalDate today = LocalDate.now(clock);
        if (startDate.isBefore(today)) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Start date ("
                            + startDate
                            + ") must not be in the past (today: "
                            + today
                            + ")");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "End date ("
                            + endDate
                            + ") must be greater than or equal to start date ("
                            + startDate
                            + ")");
        }

        Instant initialNextRunAt =
                startDate.isEqual(today)
                        ? clock.instant()
                        : startDate.atStartOfDay(clock.getZone()).toInstant();

        String normalizedActor =
                (createdBy == null || createdBy.isBlank()) ? "SYSTEM" : createdBy.trim();

        return new RecurringTransaction(
                type,
                sourceAccountId,
                targetAccountId,
                validatedMoney.getAmount(),
                location != null ? location.trim() : null,
                description != null ? description.trim() : null,
                frequency,
                startDate,
                endDate,
                initialNextRunAt,
                normalizedActor);
    }

    private static void validateAccountsByType(
            RecurringTransactionType type, Long sourceAccountId, Long targetAccountId) {
        if (type == RecurringTransactionType.DEPOSIT) {
            if (targetAccountId == null) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED,
                        "targetAccountId is required for DEPOSIT recurring transaction");
            }
            if (sourceAccountId != null) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED,
                        "sourceAccountId must be null for DEPOSIT recurring transaction");
            }
        } else if (type == RecurringTransactionType.PAYMENT) {
            if (sourceAccountId == null) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED,
                        "sourceAccountId is required for PAYMENT recurring transaction");
            }
            if (targetAccountId != null) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED,
                        "targetAccountId must be null for PAYMENT recurring transaction");
            }
        } else {
            if (sourceAccountId == null || targetAccountId == null) {
                throw new BusinessRuleException(
                        ErrorCode.VALIDATION_FAILED,
                        "Both sourceAccountId and targetAccountId are required for TRANSFER"
                                + " recurring transaction");
            }
            if (sourceAccountId.equals(targetAccountId)) {
                throw new BusinessRuleException(
                        ErrorCode.SAME_ACCOUNT_TRANSFER,
                        "Source and destination accounts cannot be identical");
            }
        }
    }

    /**
     * Pauses an {@code ACTIVE} recurring transaction instruction ({@code ACTIVE -> PAUSED}).
     *
     * @throws BusinessRuleException with {@code INVALID_STATUS_TRANSITION} (409) if not {@code
     *     ACTIVE}
     */
    public void pause() {
        if (!this.status.canTransitionTo(RecurringStatus.PAUSED)) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot pause recurring transaction in status " + this.status);
        }
        this.status = RecurringStatus.PAUSED;
    }

    /**
     * Resumes a {@code PAUSED} recurring transaction instruction ({@code PAUSED -> ACTIVE}),
     * resetting {@code failureCount} and advancing {@code nextRunAt} to {@code now} if it fell in
     * the past while paused to prevent a catch-up storm.
     *
     * @param clock application business clock
     * @throws BusinessRuleException with {@code INVALID_STATUS_TRANSITION} (409) if not {@code
     *     PAUSED} or if current business date has already passed {@code endDate}
     */
    public void resume(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!this.status.canTransitionTo(RecurringStatus.ACTIVE)) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot resume recurring transaction in status " + this.status);
        }
        LocalDate today = LocalDate.now(clock);
        if (this.endDate != null && today.isAfter(this.endDate)) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot resume recurring transaction because end date ("
                            + this.endDate
                            + ") has already passed");
        }
        Instant now = clock.instant();
        if (this.nextRunAt.isBefore(now)) {
            this.nextRunAt = now;
        }
        this.failureCount = 0;
        this.lastError = null;
        this.status = RecurringStatus.ACTIVE;
    }

    /**
     * Cancels an {@code ACTIVE} or {@code PAUSED} recurring transaction instruction ({@code ACTIVE
     * | PAUSED -> CANCELLED}).
     *
     * @throws BusinessRuleException with {@code INVALID_STATUS_TRANSITION} (409) if already {@code
     *     CANCELLED} or {@code COMPLETED}
     */
    public void cancel() {
        if (!this.status.canTransitionTo(RecurringStatus.CANCELLED)) {
            throw new BusinessRuleException(
                    ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot cancel recurring transaction in status " + this.status);
        }
        this.status = RecurringStatus.CANCELLED;
    }

    /**
     * Records a successful scheduled execution, advancing {@code nextRunAt} to the next period or
     * transitioning to {@code COMPLETED} if the next period exceeds {@code endDate} (L2-07).
     *
     * @param executedAt timestamp of execution
     * @param businessZone business timezone
     */
    public void recordSuccess(Instant executedAt, ZoneId businessZone) {
        Objects.requireNonNull(executedAt, "executedAt must not be null");
        Objects.requireNonNull(businessZone, "businessZone must not be null");
        this.lastRunAt = executedAt;
        this.failureCount = 0;
        this.lastError = null;
        advanceScheduleOrComplete(businessZone);
    }

    /**
     * Records a failed scheduled execution, incrementing {@code failureCount}, advancing {@code
     * nextRunAt} to the next period, and pausing the instruction if {@code failureCount >=
     * maxFailures} (L2-07).
     *
     * @param errorMessage reason for failure
     * @param executedAt timestamp of attempt
     * @param businessZone business timezone
     * @param maxFailures threshold at which status transitions to {@code PAUSED}
     */
    public void recordFailure(
            String errorMessage, Instant executedAt, ZoneId businessZone, int maxFailures) {
        Objects.requireNonNull(executedAt, "executedAt must not be null");
        Objects.requireNonNull(businessZone, "businessZone must not be null");
        this.lastRunAt = executedAt;
        this.failureCount += 1;
        this.lastError = truncateError(errorMessage);
        advanceScheduleOrComplete(businessZone);
        if (this.status == RecurringStatus.ACTIVE && this.failureCount >= maxFailures) {
            this.status = RecurringStatus.PAUSED;
        }
    }

    private void advanceScheduleOrComplete(ZoneId businessZone) {
        Instant candidateNext =
                this.frequency.nextInstant(
                        this.nextRunAt, this.startDate.getDayOfMonth(), businessZone);
        LocalDate nextDate = candidateNext.atZone(businessZone).toLocalDate();
        this.nextRunAt = candidateNext;
        if (this.endDate != null && nextDate.isAfter(this.endDate)) {
            this.status = RecurringStatus.COMPLETED;
        }
    }

    private static String truncateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Unknown execution error";
        }
        String trimmed = errorMessage.trim();
        return trimmed.length() > MAX_ERROR_LENGTH
                ? trimmed.substring(0, MAX_ERROR_LENGTH)
                : trimmed;
    }
}
