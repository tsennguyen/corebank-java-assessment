package com.example.corebanking.report.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.domain.ReportPeriod;
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
}
