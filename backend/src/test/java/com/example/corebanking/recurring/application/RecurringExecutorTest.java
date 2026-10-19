package com.example.corebanking.recurring.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringProperties;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import com.example.corebanking.recurring.infrastructure.RecurringTransactionRepository;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link RecurringExecutor} and {@link RecurringTransactionJob} (Story 3.6: L2-07,
 * FR-17, ADR-011).
 */
@ExtendWith(MockitoExtension.class)
class RecurringExecutorTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant FIXED_NOW = Instant.parse("2026-10-15T03:00:00Z");

    @Mock private RecurringTransactionRepository recurringRepository;
    @Mock private TransactionService transactionService;

    private RecurringProperties properties;
    private Clock clock;
    private RecurringExecutor recurringExecutor;
    private RecurringTransactionJob recurringTransactionJob;

    @BeforeEach
    void setUp() {
        properties = new RecurringProperties();
        properties.setBatchSize(50);
        properties.setMaxFailures(3);
        properties.setSchedulerEnabled(true);
        clock = Clock.fixed(FIXED_NOW, VN_ZONE);
        recurringExecutor =
                new RecurringExecutor(recurringRepository, transactionService, properties, clock);
        recurringTransactionJob = new RecurringTransactionJob(recurringExecutor, properties);
    }

    @Test
    @DisplayName(
            "executeNextDue executes due DEPOSIT with REC-{id}-{yyyyMMdd} and advances nextRunAt")
    void executeNextDue_whenDepositDue_shouldExecuteAndAdvanceNextRunAt() {
        LocalDate today = LocalDate.now(clock);
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        RecurringTransactionType.DEPOSIT,
                        null,
                        101L,
                        new BigDecimal("1500000.00"),
                        RecurringFrequency.DAILY,
                        today,
                        today.plusDays(10),
                        "HQ",
                        "Daily deposit",
                        "admin",
                        clock);
        ReflectionTestUtils.setField(recurring, "id", 15L);

        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenReturn(Optional.of(recurring));

        boolean processed = recurringExecutor.executeNextDue();

        assertThat(processed).isTrue();
        verify(transactionService)
                .executeScheduledTransaction(
                        eq(TransactionType.DEPOSIT),
                        eq("REC-15-20261015"),
                        eq(15L),
                        eq(null),
                        eq(101L),
                        eq(new BigDecimal("1500000.00")),
                        eq("HQ"),
                        eq("Daily deposit"));
        verify(recurringRepository).saveAndFlush(recurring);
        assertThat(recurring.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(recurring.getFailureCount()).isZero();
        assertThat(recurring.getLastError()).isNull();
        assertThat(recurring.getLastRunAt()).isEqualTo(FIXED_NOW);
        assertThat(recurring.getNextRunAt().atZone(VN_ZONE).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    @DisplayName("executeNextDue transitions to COMPLETED when next period exceeds endDate")
    void executeNextDue_whenNextRunExceedsEndDate_shouldTransitionToCompleted() {
        LocalDate today = LocalDate.now(clock);
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        RecurringTransactionType.PAYMENT,
                        201L,
                        null,
                        new BigDecimal("500000.00"),
                        RecurringFrequency.DAILY,
                        today,
                        today,
                        "Mobile",
                        "Final payment",
                        "admin",
                        clock);
        ReflectionTestUtils.setField(recurring, "id", 22L);

        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenReturn(Optional.of(recurring));

        boolean processed = recurringExecutor.executeNextDue();

        assertThat(processed).isTrue();
        assertThat(recurring.getStatus()).isEqualTo(RecurringStatus.COMPLETED);
        assertThat(recurring.getLastRunAt()).isEqualTo(FIXED_NOW);
        verify(recurringRepository).saveAndFlush(recurring);
    }

    @Test
    @DisplayName(
            "executeNextDue increments failureCount, records lastError, and advances nextRunAt on"
                    + " INSUFFICIENT_BALANCE")
    void executeNextDue_whenInsufficientBalance_shouldIncrementFailureCountAndAdvanceNextRunAt() {
        LocalDate today = LocalDate.now(clock);
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        RecurringTransactionType.TRANSFER,
                        101L,
                        102L,
                        new BigDecimal("5000000.00"),
                        RecurringFrequency.WEEKLY,
                        today,
                        null,
                        "Branch",
                        "Weekly transfer",
                        "admin",
                        clock);
        ReflectionTestUtils.setField(recurring, "id", 30L);

        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenReturn(Optional.of(recurring));
        doThrow(
                        new BusinessRuleException(
                                ErrorCode.INSUFFICIENT_BALANCE,
                                "Account 100000000019 has insufficient balance"))
                .when(transactionService)
                .executeScheduledTransaction(
                        eq(TransactionType.TRANSFER),
                        eq("REC-30-20261015"),
                        eq(30L),
                        eq(101L),
                        eq(102L),
                        eq(new BigDecimal("5000000.00")),
                        eq("Branch"),
                        eq("Weekly transfer"));

        boolean processed = recurringExecutor.executeNextDue();

        assertThat(processed).isTrue();
        assertThat(recurring.getFailureCount()).isEqualTo(1);
        assertThat(recurring.getStatus()).isEqualTo(RecurringStatus.ACTIVE);
        assertThat(recurring.getLastError()).startsWith("INSUFFICIENT_BALANCE:");
        assertThat(recurring.getNextRunAt().atZone(VN_ZONE).toLocalDate())
                .isEqualTo(today.plusWeeks(1));
        verify(recurringRepository).saveAndFlush(recurring);
    }

    @Test
    @DisplayName(
            "executeNextDue transitions instruction to PAUSED on third consecutive business"
                    + " failure")
    void executeNextDue_whenThirdConsecutiveFailure_shouldTransitionToPaused() {
        LocalDate today = LocalDate.now(clock);
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        RecurringTransactionType.PAYMENT,
                        101L,
                        null,
                        new BigDecimal("2000000.00"),
                        RecurringFrequency.DAILY,
                        today,
                        null,
                        null,
                        "Utility bill",
                        "admin",
                        clock);
        ReflectionTestUtils.setField(recurring, "id", 41L);
        ReflectionTestUtils.setField(recurring, "failureCount", 2);

        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenReturn(Optional.of(recurring));
        doThrow(new BusinessRuleException(ErrorCode.ACCOUNT_NOT_ACTIVE, "Account is locked"))
                .when(transactionService)
                .executeScheduledTransaction(
                        any(), any(), any(), any(), any(), any(), any(), any());

        boolean processed = recurringExecutor.executeNextDue();

        assertThat(processed).isTrue();
        assertThat(recurring.getFailureCount()).isEqualTo(3);
        assertThat(recurring.getStatus()).isEqualTo(RecurringStatus.PAUSED);
        assertThat(recurring.getLastError()).contains("ACCOUNT_NOT_ACTIVE");
        verify(recurringRepository).saveAndFlush(recurring);
    }

    @Test
    @DisplayName(
            "executeNextDue records RESOURCE_NOT_FOUND failure when referenced account is missing")
    void executeNextDue_whenAccountNotFound_shouldRecordFailure() {
        LocalDate today = LocalDate.now(clock);
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        RecurringTransactionType.DEPOSIT,
                        null,
                        999L,
                        new BigDecimal("100000.00"),
                        RecurringFrequency.DAILY,
                        today,
                        null,
                        null,
                        null,
                        "admin",
                        clock);
        ReflectionTestUtils.setField(recurring, "id", 55L);

        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenReturn(Optional.of(recurring));
        doThrow(new ResourceNotFoundException("Account", 999L))
                .when(transactionService)
                .executeScheduledTransaction(
                        any(), any(), any(), any(), any(), any(), any(), any());

        boolean processed = recurringExecutor.executeNextDue();

        assertThat(processed).isTrue();
        assertThat(recurring.getFailureCount()).isEqualTo(1);
        assertThat(recurring.getLastError()).startsWith("RESOURCE_NOT_FOUND:");
        verify(recurringRepository).saveAndFlush(recurring);
    }

    @Test
    @DisplayName(
            "RecurringTransactionJob stops batch loop when no due instructions remain or on lock"
                    + " contention, and skips when schedulerEnabled=false")
    void recurringTransactionJob_batchLoopAndSchedulerFlag_shouldBehaveCorrectly() {
        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenReturn(Optional.empty());
        assertThat(recurringTransactionJob.runDueTransactions()).isZero();
        verify(recurringRepository, times(1)).findNextDueForUpdateSkipLocked(FIXED_NOW);

        // When schedulerEnabled is false, runScheduled() is a no-op
        properties.setSchedulerEnabled(false);
        recurringTransactionJob.runScheduled();
        verify(recurringRepository, times(1)).findNextDueForUpdateSkipLocked(FIXED_NOW);

        // When PessimisticLockingFailureException occurs, runDueTransactions breaks cleanly
        properties.setSchedulerEnabled(true);
        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenThrow(new PessimisticLockingFailureException("Lock timeout"));
        assertThat(recurringTransactionJob.runDueTransactions()).isZero();
        verify(transactionService, never())
                .executeScheduledTransaction(
                        any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName(
            "executeNextDue increments banking.recurring.executed counter with SUCCESS and FAILURE"
                    + " outcome tags (Story 4.8: L3-09, FR-24)")
    void executeNextDue_withMeterRegistry_shouldIncrementRecurringExecutedCounterByOutcome() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        RecurringExecutor meteredExecutor =
                new RecurringExecutor(
                        recurringRepository, transactionService, properties, clock, meterRegistry);

        LocalDate today = LocalDate.now(clock);
        RecurringTransaction recurring =
                RecurringTransaction.create(
                        RecurringTransactionType.DEPOSIT,
                        null,
                        101L,
                        new BigDecimal("500000.00"),
                        RecurringFrequency.DAILY,
                        today,
                        null,
                        "HQ",
                        "Metric recurring deposit",
                        "admin",
                        clock);
        ReflectionTestUtils.setField(recurring, "id", 77L);

        when(recurringRepository.findNextDueForUpdateSkipLocked(FIXED_NOW))
                .thenReturn(Optional.of(recurring));

        assertThat(meteredExecutor.executeNextDue()).isTrue();
        assertThat(
                        meterRegistry
                                .get(RecurringExecutor.RECURRING_EXECUTED_METRIC)
                                .tag("outcome", "SUCCESS")
                                .counter()
                                .count())
                .isEqualTo(1.0);

        doThrow(new BusinessRuleException(ErrorCode.INSUFFICIENT_BALANCE, "Low funds"))
                .when(transactionService)
                .executeScheduledTransaction(
                        any(), any(), any(), any(), any(), any(), any(), any());

        assertThat(meteredExecutor.executeNextDue()).isTrue();
        assertThat(
                        meterRegistry
                                .get(RecurringExecutor.RECURRING_EXECUTED_METRIC)
                                .tag("outcome", "FAILURE")
                                .counter()
                                .count())
                .isEqualTo(1.0);
    }
}
