package com.example.corebanking.report.application;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.infrastructure.ReportRepository;
import com.example.corebanking.transaction.domain.TransactionType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service providing periodic transaction analytics and date-range boundary validation
 * (L3-01, L3-02, G-05, FR-19).
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** Maximum permitted span in days between {@code from} and {@code to} (inclusive window). */
    public static final long MAX_REPORT_RANGE_DAYS = 366L;

    private final ReportRepository reportRepository;
    private final Clock clock;

    /**
     * Computes aggregated transaction metrics per {@link ReportPeriod} bucket within the inclusive
     * date range {@code [from, to]} evaluated in the configured business timezone.
     *
     * @param period aggregation period ({@code WEEK}, {@code QUARTER}, or {@code YEAR})
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter (cannot be {@code REVERSAL})
     * @return chronological list of periodic report buckets
     */
    @Transactional(readOnly = true)
    public List<TransactionReportBucketResponse> getPeriodicTransactionReport(
            ReportPeriod period, LocalDate from, LocalDate to, TransactionType type) {
        if (period == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Report period is required");
        }
        validateDateRange(from, to);

        if (type == TransactionType.REVERSAL) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "REVERSAL transactions are excluded from periodic volume reports and cannot be"
                            + " used as a filter");
        }

        ZoneId businessZone = clock.getZone();
        Instant fromInstant = from.atStartOfDay(businessZone).toInstant();
        Instant toExclusiveInstant = to.plusDays(1).atStartOfDay(businessZone).toInstant();

        return reportRepository.findPeriodicTransactionReport(
                period, fromInstant, toExclusiveInstant, type, businessZone);
    }

    /**
     * Validates that {@code from} and {@code to} are non-null, ordered ({@code
     * !to.isBefore(from)}), and span at most {@link #MAX_REPORT_RANGE_DAYS} days.
     *
     * @param from inclusive start date
     * @param to inclusive end date
     */
    public void validateDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Both 'from' and 'to' dates are required");
        }
        if (to.isBefore(from)) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "End date 'to' must not be before start date 'from'");
        }
        long daysBetween = ChronoUnit.DAYS.between(from, to);
        if (daysBetween > MAX_REPORT_RANGE_DAYS) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Report date range cannot exceed " + MAX_REPORT_RANGE_DAYS + " days");
        }
    }
}
