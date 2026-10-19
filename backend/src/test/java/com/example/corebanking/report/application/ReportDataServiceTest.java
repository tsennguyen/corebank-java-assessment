package com.example.corebanking.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.report.api.dto.ReportBundle;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@ExtendWith(MockitoExtension.class)
class ReportDataServiceTest {

    @Mock private ReportService reportService;

    private ReportDataService reportDataService;

    @BeforeEach
    void setUp() {
        SimpleAsyncTaskExecutor vtExecutor = new SimpleAsyncTaskExecutor("vt-report-test-");
        vtExecutor.setVirtualThreads(true);
        reportDataService = new ReportDataService(reportService, vtExecutor);
    }

    @Test
    @DisplayName(
            "loadReportBundle executes periodic report and trend series queries concurrently on"
                    + " Virtual Threads (AC 4, L3-10)")
    void loadReportBundle_shouldRunQueriesOnVirtualThreadsAndReturnBundle() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);

        AtomicBoolean periodicOnVirtualThread = new AtomicBoolean(false);
        AtomicBoolean trendsOnVirtualThread = new AtomicBoolean(false);

        List<TransactionReportBucketResponse> expectedPeriodic =
                List.of(
                        new TransactionReportBucketResponse(
                                LocalDate.of(2026, 1, 1),
                                4L,
                                new BigDecimal("12000000.00"),
                                new BigDecimal("3000000.00"),
                                new BigDecimal("5000000.00"),
                                new BigDecimal("1000000.00"),
                                new BigDecimal("6000.00")));
        List<TrendPointResponse> expectedTrends =
                List.of(
                        new TrendPointResponse(
                                LocalDate.of(2026, 1, 1),
                                4L,
                                new BigDecimal("12000000.00"),
                                new BigDecimal("6000.00"),
                                new BigDecimal("250000000.00")));

        when(reportService.getPeriodicTransactionReport(ReportPeriod.QUARTER, from, to, null))
                .thenAnswer(
                        inv -> {
                            periodicOnVirtualThread.set(Thread.currentThread().isVirtual());
                            return expectedPeriodic;
                        });
        when(reportService.getTrendSeries(TrendGranularity.WEEK, from, to, null))
                .thenAnswer(
                        inv -> {
                            trendsOnVirtualThread.set(Thread.currentThread().isVirtual());
                            return expectedTrends;
                        });

        ReportBundle bundle =
                reportDataService.loadReportBundle(ReportPeriod.QUARTER, from, to, null);

        assertThat(bundle.periodicBuckets()).isEqualTo(expectedPeriodic);
        assertThat(bundle.trendPoints()).isEqualTo(expectedTrends);
        assertThat(periodicOnVirtualThread.get()).isTrue();
        assertThat(trendsOnVirtualThread.get()).isTrue();
    }

    @Test
    @DisplayName("loadReportBundle unwraps RuntimeException from async tasks cleanly")
    void loadReportBundle_whenAsyncTaskThrowsDomainException_shouldUnwrapAndRethrow() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);

        when(reportService.getPeriodicTransactionReport(ReportPeriod.WEEK, from, to, null))
                .thenReturn(List.of());
        when(reportService.getTrendSeries(TrendGranularity.DAY, from, to, 999L))
                .thenThrow(new ResourceNotFoundException("Account", 999L));

        assertThatThrownBy(
                        () ->
                                reportDataService.loadReportBundle(
                                        ReportPeriod.WEEK,
                                        TrendGranularity.DAY,
                                        from,
                                        to,
                                        null,
                                        999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("loadReportBundle rejects null period or granularity with 400 VALIDATION_FAILED")
    void loadReportBundle_whenPeriodOrGranularityNull_shouldThrowValidationFailed() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 14);

        assertThatThrownBy(
                        () ->
                                reportDataService.loadReportBundle(
                                        null, TrendGranularity.DAY, from, to, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                reportDataService.loadReportBundle(
                                        ReportPeriod.WEEK, null, from, to, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("defaultGranularityFor maps WEEK->DAY, QUARTER->WEEK, YEAR->MONTH")
    void defaultGranularityFor_shouldMapPeriodsCorrectly() {
        assertThat(ReportDataService.defaultGranularityFor(ReportPeriod.WEEK))
                .isEqualTo(TrendGranularity.DAY);
        assertThat(ReportDataService.defaultGranularityFor(ReportPeriod.QUARTER))
                .isEqualTo(TrendGranularity.WEEK);
        assertThat(ReportDataService.defaultGranularityFor(ReportPeriod.YEAR))
                .isEqualTo(TrendGranularity.MONTH);
        assertThat(ReportDataService.defaultGranularityFor(null)).isEqualTo(TrendGranularity.DAY);
    }

    @Test
    @DisplayName("streamTransactionsInPeriod delegates to ReportService (L3-07)")
    void streamTransactionsInPeriod_shouldDelegateToReportService() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        java.util.function.Consumer<com.example.corebanking.report.api.dto.TransactionExportRow>
                consumer = row -> {};

        reportDataService.streamTransactionsInPeriod(from, to, TransactionType.DEPOSIT, consumer);
        org.mockito.Mockito.verify(reportService)
                .streamTransactionsInPeriod(from, to, TransactionType.DEPOSIT, consumer);

        reportDataService.streamTransactionsInPeriod(
                from, to, TransactionType.DEPOSIT, 1000, consumer);
        org.mockito.Mockito.verify(reportService)
                .streamTransactionsInPeriod(from, to, TransactionType.DEPOSIT, 1000, consumer);
    }
}
