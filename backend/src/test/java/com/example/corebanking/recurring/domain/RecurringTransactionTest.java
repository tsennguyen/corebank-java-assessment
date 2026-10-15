package com.example.corebanking.recurring.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecurringTransactionTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant FIXED_NOW =
            ZonedDateTime.of(2026, 10, 15, 10, 30, 0, 0, VN_ZONE).toInstant();
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, VN_ZONE);

    @Test
    @DisplayName(
            "create when startDate is today should set nextRunAt immediately to clock.instant()")
    void create_whenStartDateIsToday_shouldSetNextRunAtToNow() {
        RecurringTransaction rec =
                RecurringTransaction.create(
                        RecurringTransactionType.PAYMENT,
                        10L,
                        null,
                        new BigDecimal("1500000.00"),
                        RecurringFrequency.MONTHLY,
                        LocalDate.of(2026, 10, 15),
                        LocalDate.of(2027, 10, 15),
                        "Hà Nội",
                        "Thanh toán tiền điện hàng tháng",
                        "admin",
                        FIXED_CLOCK);

        assertThat(rec.getType()).isEqualTo(RecurringTransactionType.PAYMENT);
        assertThat(rec.getType().toTransactionType()).isEqualTo(TransactionType.PAYMENT);
        assertThat(rec.getSourceAccountId()).isEqualTo(10L);
        assertThat(rec.getTargetAccountId()).isNull();
        assertThat(rec.getAmount()).isEqualByComparingTo("1500000.00");
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(rec.getFailureCount()).isZero();
        assertThat(rec.getNextRunAt()).isEqualTo(FIXED_NOW);
        assertThat(rec.getCreatedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName(
            "create when startDate is in future should set nextRunAt to startDate 00:00 Vietnam"
                    + " time")
    void create_whenStartDateInFuture_shouldSetNextRunAtToMidnightVnTime() {
        LocalDate futureStart = LocalDate.of(2026, 11, 1);
        RecurringTransaction rec =
                RecurringTransaction.create(
                        RecurringTransactionType.DEPOSIT,
                        null,
                        20L,
                        new BigDecimal("5000000.00"),
                        RecurringFrequency.WEEKLY,
                        futureStart,
                        null,
                        null,
                        "Tiết kiệm định kỳ",
                        " ",
                        FIXED_CLOCK);

        assertThat(rec.getNextRunAt()).isEqualTo(futureStart.atStartOfDay(VN_ZONE).toInstant());
        assertThat(rec.getCreatedBy()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName(
            "create should enforce account requirements per type and reject identical transfer"
                    + " accounts")
    void create_whenInvalidAccountsForType_shouldThrowBusinessRuleException() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        BigDecimal amount = new BigDecimal("100000.00");

        // DEPOSIT missing targetAccountId
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.DEPOSIT,
                                        null,
                                        null,
                                        amount,
                                        RecurringFrequency.DAILY,
                                        today,
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // DEPOSIT with unexpected sourceAccountId
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.DEPOSIT,
                                        1L,
                                        2L,
                                        amount,
                                        RecurringFrequency.DAILY,
                                        today,
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // PAYMENT missing sourceAccountId
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.PAYMENT,
                                        null,
                                        null,
                                        amount,
                                        RecurringFrequency.DAILY,
                                        today,
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // PAYMENT with unexpected targetAccountId
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.PAYMENT,
                                        1L,
                                        2L,
                                        amount,
                                        RecurringFrequency.DAILY,
                                        today,
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // TRANSFER missing targetAccountId
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.TRANSFER,
                                        1L,
                                        null,
                                        amount,
                                        RecurringFrequency.DAILY,
                                        today,
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // TRANSFER with same source and target account -> SAME_ACCOUNT_TRANSFER (400)
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.TRANSFER,
                                        5L,
                                        5L,
                                        amount,
                                        RecurringFrequency.DAILY,
                                        today,
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SAME_ACCOUNT_TRANSFER);
    }

    @Test
    @DisplayName(
            "create should reject past startDate, endDate < startDate, and non-positive amount")
    void create_whenDatesOrAmountInvalid_shouldThrowValidationFailed() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        BigDecimal validAmount = new BigDecimal("100000.00");

        // startDate in the past
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.PAYMENT,
                                        1L,
                                        null,
                                        validAmount,
                                        RecurringFrequency.MONTHLY,
                                        today.minusDays(1),
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // endDate < startDate
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.PAYMENT,
                                        1L,
                                        null,
                                        validAmount,
                                        RecurringFrequency.MONTHLY,
                                        today.plusDays(5),
                                        today.plusDays(2),
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        // amount <= 0
        assertThatThrownBy(
                        () ->
                                RecurringTransaction.create(
                                        RecurringTransactionType.PAYMENT,
                                        1L,
                                        null,
                                        BigDecimal.ZERO,
                                        RecurringFrequency.MONTHLY,
                                        today,
                                        null,
                                        null,
                                        null,
                                        "admin",
                                        FIXED_CLOCK))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName(
            "pause, resume, and cancel should enforce FSM rules and prevent catch-up storm on"
                    + " resume")
    void lifecycleTransitions_shouldEnforceFsmAndCatchUpProtection() {
        RecurringTransaction rec =
                RecurringTransaction.create(
                        RecurringTransactionType.TRANSFER,
                        1L,
                        2L,
                        new BigDecimal("2000000.00"),
                        RecurringFrequency.DAILY,
                        LocalDate.of(2026, 10, 15),
                        LocalDate.of(2026, 10, 20),
                        null,
                        "Chuyển khoản định kỳ",
                        "admin",
                        FIXED_CLOCK);

        // ACTIVE -> PAUSED
        rec.pause();
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.PAUSED);

        // PAUSED -> PAUSED rejected (409)
        assertThatThrownBy(rec::pause)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

        // Resume 2 days later -> nextRunAt advances to laterNow to prevent catch-up storm
        Instant laterNow = ZonedDateTime.of(2026, 10, 17, 9, 0, 0, 0, VN_ZONE).toInstant();
        Clock laterClock = Clock.fixed(laterNow, VN_ZONE);
        rec.resume(laterClock);
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(rec.getNextRunAt()).isEqualTo(laterNow);

        // Resume when already ACTIVE rejected (409)
        assertThatThrownBy(() -> rec.resume(laterClock))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

        // Pause then try to resume after endDate (2026-10-21 > 2026-10-20) -> 409
        rec.pause();
        Clock expiredClock =
                Clock.fixed(
                        ZonedDateTime.of(2026, 10, 21, 9, 0, 0, 0, VN_ZONE).toInstant(), VN_ZONE);
        assertThatThrownBy(() -> rec.resume(expiredClock))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);

        // Cancel from PAUSED -> CANCELLED
        rec.cancel();
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.CANCELLED);

        // Terminal state CANCELLED cannot resume, pause, or cancel again (409)
        assertThatThrownBy(() -> rec.resume(laterClock))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
        assertThatThrownBy(rec::pause)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
        assertThatThrownBy(rec::cancel)
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    @DisplayName(
            "recordSuccess and recordFailure should advance schedule, auto-pause at maxFailures,"
                    + " and mark COMPLETED past endDate")
    void recordSuccessAndFailure_shouldAdvanceScheduleAndRespectThresholds() {
        RecurringTransaction rec =
                RecurringTransaction.create(
                        RecurringTransactionType.PAYMENT,
                        1L,
                        null,
                        new BigDecimal("500000.00"),
                        RecurringFrequency.DAILY,
                        LocalDate.of(2026, 10, 15),
                        LocalDate.of(2026, 10, 17),
                        null,
                        null,
                        "admin",
                        FIXED_CLOCK);

        // 1st failure (nextRunAt -> 2026-10-16)
        rec.recordFailure("Insufficient balance", FIXED_NOW, VN_ZONE, 3);
        assertThat(rec.getFailureCount()).isEqualTo(1);
        assertThat(rec.getLastError()).isEqualTo("Insufficient balance");
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(rec.getNextRunAt())
                .isEqualTo(LocalDate.of(2026, 10, 16).atStartOfDay(VN_ZONE).toInstant());

        // Success on 2026-10-16 resets failureCount and advances nextRunAt to 2026-10-17
        Instant secondRun = ZonedDateTime.of(2026, 10, 16, 0, 1, 0, 0, VN_ZONE).toInstant();
        rec.recordSuccess(secondRun, VN_ZONE);
        assertThat(rec.getFailureCount()).isZero();
        assertThat(rec.getLastError()).isNull();
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(rec.getNextRunAt())
                .isEqualTo(LocalDate.of(2026, 10, 17).atStartOfDay(VN_ZONE).toInstant());

        // Success on 2026-10-17 advances nextRunAt to 2026-10-18 > endDate (2026-10-17) ->
        // COMPLETED
        Instant thirdRun = ZonedDateTime.of(2026, 10, 17, 0, 1, 0, 0, VN_ZONE).toInstant();
        rec.recordSuccess(thirdRun, VN_ZONE);
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.COMPLETED);
    }

    @Test
    @DisplayName("recordFailure reaching 3 consecutive failures should transition to PAUSED")
    void recordFailure_whenReachingThreeFailures_shouldTransitionToPaused() {
        RecurringTransaction rec =
                RecurringTransaction.create(
                        RecurringTransactionType.PAYMENT,
                        1L,
                        null,
                        new BigDecimal("500000.00"),
                        RecurringFrequency.DAILY,
                        LocalDate.of(2026, 10, 15),
                        null,
                        null,
                        null,
                        "admin",
                        FIXED_CLOCK);

        rec.recordFailure("Error 1", FIXED_NOW, VN_ZONE, 3);
        rec.recordFailure("Error 2", FIXED_NOW, VN_ZONE, 3);
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.ACTIVE);

        rec.recordFailure("Error 3", FIXED_NOW, VN_ZONE, 3);
        assertThat(rec.getFailureCount()).isEqualTo(3);
        assertThat(rec.getStatus()).isEqualTo(RecurringStatus.PAUSED);
    }
}
