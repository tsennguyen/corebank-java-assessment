package com.example.corebanking.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.report.infrastructure.ReportRepository;
import com.example.corebanking.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    @Mock private ReportRepository reportRepository;

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), VN_ZONE);
        reportService = new ReportService(reportRepository, clock);
    }

    @Test
    @DisplayName(
            "getPeriodicTransactionReport converts inclusive LocalDate range to half-open"
                    + " Asia/Ho_Chi_Minh instants and delegates to repository")
    void getPeriodicReport_whenValidRange_shouldConvertDatesUsingBusinessTimezoneAndDelegate() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        Instant expectedFrom = Instant.parse("2025-12-31T17:00:00Z"); // 2026-01-01 00:00+07:00
        Instant expectedToExclusive =
                Instant.parse("2026-06-30T17:00:00Z"); // 2026-07-01 00:00+07:00

        List<TransactionReportBucketResponse> expected =
                List.of(
                        new TransactionReportBucketResponse(
                                LocalDate.of(2026, 1, 1),
                                3L,
                                new BigDecimal("6000000.00"),
                                new BigDecimal("2000000.00"),
                                new BigDecimal("3000000.00"),
                                new BigDecimal("1000000.00"),
                                new BigDecimal("4000.00")));

        when(reportRepository.findPeriodicTransactionReport(
                        ReportPeriod.QUARTER,
                        expectedFrom,
                        expectedToExclusive,
                        TransactionType.TRANSFER,
                        VN_ZONE))
                .thenReturn(expected);

        List<TransactionReportBucketResponse> actual =
                reportService.getPeriodicTransactionReport(
                        ReportPeriod.QUARTER, from, to, TransactionType.TRANSFER);

        assertThat(actual).isEqualTo(expected);
        verify(reportRepository)
                .findPeriodicTransactionReport(
                        ReportPeriod.QUARTER,
                        expectedFrom,
                        expectedToExclusive,
                        TransactionType.TRANSFER,
                        VN_ZONE);
    }

    @Test
    @DisplayName("getPeriodicTransactionReport allows 366-day difference boundary without error")
    void getPeriodicReport_whenRangeIsExactly366Days_shouldSucceed() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(366);
        Instant expectedFrom = from.atStartOfDay(VN_ZONE).toInstant();
        Instant expectedToExclusive = to.plusDays(1).atStartOfDay(VN_ZONE).toInstant();

        when(reportRepository.findPeriodicTransactionReport(
                        ReportPeriod.YEAR, expectedFrom, expectedToExclusive, null, VN_ZONE))
                .thenReturn(List.of());

        List<TransactionReportBucketResponse> result =
                reportService.getPeriodicTransactionReport(ReportPeriod.YEAR, from, to, null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName(
            "getPeriodicTransactionReport throws 400 VALIDATION_FAILED when 'to' is before 'from'")
    void getPeriodicReport_whenToBeforeFrom_shouldThrowValidationFailed() {
        LocalDate from = LocalDate.of(2026, 6, 30);
        LocalDate to = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(
                        () ->
                                reportService.getPeriodicTransactionReport(
                                        ReportPeriod.QUARTER, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("must not be before");

        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName(
            "getPeriodicTransactionReport throws 400 VALIDATION_FAILED when range exceeds 366 days")
    void getPeriodicReport_whenRangeExceeds366Days_shouldThrowValidationFailed() {
        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to = from.plusDays(367);

        assertThatThrownBy(
                        () ->
                                reportService.getPeriodicTransactionReport(
                                        ReportPeriod.YEAR, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("366 days");

        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName(
            "getPeriodicTransactionReport throws 400 VALIDATION_FAILED when period, from, or to is"
                    + " null")
    void getPeriodicReport_whenRequiredArgNull_shouldThrowValidationFailed() {
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate d2 = LocalDate.of(2026, 3, 31);

        assertThatThrownBy(() -> reportService.getPeriodicTransactionReport(null, d1, d2, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                reportService.getPeriodicTransactionReport(
                                        ReportPeriod.WEEK, null, d2, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(
                        () ->
                                reportService.getPeriodicTransactionReport(
                                        ReportPeriod.WEEK, d1, null, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("getPeriodicTransactionReport throws 400 VALIDATION_FAILED when type is REVERSAL")
    void getPeriodicReport_whenTypeIsReversal_shouldThrowValidationFailed() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        assertThatThrownBy(
                        () ->
                                reportService.getPeriodicTransactionReport(
                                        ReportPeriod.QUARTER, from, to, TransactionType.REVERSAL))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("REVERSAL");

        verifyNoInteractions(reportRepository);
    }

    @Test
    @DisplayName("ReportPeriod.sqlUnit returns lowercase PostgreSQL date_trunc unit strings")
    void reportPeriod_sqlUnit_shouldReturnExpectedUnits() {
        assertThat(ReportPeriod.WEEK.sqlUnit()).isEqualTo("week");
        assertThat(ReportPeriod.QUARTER.sqlUnit()).isEqualTo("quarter");
        assertThat(ReportPeriod.YEAR.sqlUnit()).isEqualTo("year");
    }

    @Test
    @DisplayName(
            "getTrendSeries delegates to findSystemTrendSeries when accountId is null (AC 1,"
                    + " L3-08)")
    void getTrendSeries_whenAccountIdNull_shouldDelegateToSystemTrends() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);
        Instant expectedFrom = from.atStartOfDay(VN_ZONE).toInstant();
        Instant expectedToExclusive = to.plusDays(1).atStartOfDay(VN_ZONE).toInstant();

        List<TrendPointResponse> expected =
                List.of(
                        new TrendPointResponse(
                                from,
                                2L,
                                new BigDecimal("5000000.00"),
                                new BigDecimal("2000.00"),
                                new BigDecimal("100000000.00")));

        when(reportRepository.findSystemTrendSeries(
                        TrendGranularity.DAY, from, to, expectedFrom, expectedToExclusive, VN_ZONE))
                .thenReturn(expected);

        List<TrendPointResponse> actual =
                reportService.getTrendSeries(TrendGranularity.DAY, from, to, null);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName(
            "getTrendSeries checks account existence and delegates to findAccountTrendSeries when"
                    + " accountId is provided (AC 2, L3-08)")
    void getTrendSeries_whenAccountIdExists_shouldDelegateToAccountTrends() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);
        Instant expectedFrom = from.atStartOfDay(VN_ZONE).toInstant();
        Instant expectedToExclusive = to.plusDays(1).atStartOfDay(VN_ZONE).toInstant();

        List<TrendPointResponse> expected =
                List.of(
                        new TrendPointResponse(
                                from,
                                1L,
                                new BigDecimal("3000000.00"),
                                new BigDecimal("0.00"),
                                new BigDecimal("13000000.00")));

        when(reportRepository.existsAccountById(42L)).thenReturn(true);
        when(reportRepository.findAccountTrendSeries(
                        TrendGranularity.DAY,
                        42L,
                        from,
                        to,
                        expectedFrom,
                        expectedToExclusive,
                        VN_ZONE))
                .thenReturn(expected);

        List<TrendPointResponse> actual =
                reportService.getTrendSeries(TrendGranularity.DAY, from, to, 42L);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("getTrendSeries throws 404 RESOURCE_NOT_FOUND when accountId does not exist")
    void getTrendSeries_whenAccountIdDoesNotExist_shouldThrowResourceNotFound() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);

        when(reportRepository.existsAccountById(999L)).thenReturn(false);

        assertThatThrownBy(() -> reportService.getTrendSeries(TrendGranularity.DAY, from, to, 999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("getTrendSeries throws 400 VALIDATION_FAILED when granularity is null")
    void getTrendSeries_whenGranularityNull_shouldThrowValidationFailed() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 3);

        assertThatThrownBy(() -> reportService.getTrendSeries(null, from, to, null))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("TrendGranularity returns expected sqlUnit and sqlStep values")
    void trendGranularity_shouldReturnExpectedSqlLiterals() {
        assertThat(TrendGranularity.DAY.sqlUnit()).isEqualTo("day");
        assertThat(TrendGranularity.DAY.sqlStep()).isEqualTo("1 day");
        assertThat(TrendGranularity.WEEK.sqlUnit()).isEqualTo("week");
        assertThat(TrendGranularity.WEEK.sqlStep()).isEqualTo("1 week");
        assertThat(TrendGranularity.MONTH.sqlUnit()).isEqualTo("month");
        assertThat(TrendGranularity.MONTH.sqlStep()).isEqualTo("1 month");
    }

    @Test
    @DisplayName(
            "streamTransactionsInPeriod converts Vietnam date bounds to UTC Instants and rejects"
                    + " REVERSAL filter (AC 2, AC 3, L3-07)")
    void streamTransactionsInPeriod_shouldDelegateWithZoneBoundsAndRejectReversal() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        Instant expectedFrom = from.atStartOfDay(VN_ZONE).toInstant();
        Instant expectedToExclusive = to.plusDays(1).atStartOfDay(VN_ZONE).toInstant();

        java.util.function.Consumer<com.example.corebanking.report.api.dto.TransactionExportRow>
                consumer = row -> {};

        reportService.streamTransactionsInPeriod(from, to, TransactionType.TRANSFER, consumer);

        org.mockito.Mockito.verify(reportRepository)
                .streamTransactionsInPeriod(
                        expectedFrom,
                        expectedToExclusive,
                        TransactionType.TRANSFER,
                        null,
                        consumer);

        assertThatThrownBy(
                        () ->
                                reportService.streamTransactionsInPeriod(
                                        from, to, TransactionType.REVERSAL, consumer))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("REVERSAL");
    }
}
