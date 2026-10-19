package com.example.corebanking.report.application;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.report.api.dto.TransactionExportRow;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.report.infrastructure.ReportRepository;
import com.example.corebanking.transaction.domain.TransactionType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service providing periodic transaction analytics, time-series trend reconstructions,
 * streaming transaction detail exports, and date-range boundary validation (L3-01, L3-02, L3-07,
 * L3-08, G-05, FR-19, FR-20, FR-21).
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** Maximum permitted span in days between {@code from} and {@code to} (inclusive window). */
    public static final long MAX_REPORT_RANGE_DAYS = 366L;

    private final ReportRepository reportRepository;
    private final Clock clock;

    /**
     * Streams completed business transactions within {@code [from, to]} inside a read-only database
     * transaction so PostgreSQL opens a server-side cursor ({@code fetchSize = 1000}) instead of
     * loading rows into heap memory (AC 2, L3-07, G-05, NFR-2, ADR-014).
     *
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter
     * @param rowConsumer callback invoked for each transaction row
     */
    @Transactional(readOnly = true)
    public void streamTransactionsInPeriod(
            LocalDate from,
            LocalDate to,
            TransactionType type,
            Consumer<TransactionExportRow> rowConsumer) {
        streamTransactionsInPeriod(from, to, type, null, rowConsumer);
    }

    /**
     * Streams completed business transactions within {@code [from, to]} with an optional row cap
     * inside a read-only database transaction (L3-07, G-05, FR-21).
     *
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter
     * @param maxRows optional maximum number of rows to stream
     * @param rowConsumer callback invoked for each transaction row
     */
    @Transactional(readOnly = true)
    public void streamTransactionsInPeriod(
            LocalDate from,
            LocalDate to,
            TransactionType type,
            Integer maxRows,
            Consumer<TransactionExportRow> rowConsumer) {
        validateDateRange(from, to);
        if (type == TransactionType.REVERSAL) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "REVERSAL transactions are excluded from financial report exports and cannot be"
                            + " used as a filter");
        }
        ZoneId businessZone = clock.getZone();
        Instant fromInstant = from.atStartOfDay(businessZone).toInstant();
        Instant toExclusiveInstant = to.plusDays(1).atStartOfDay(businessZone).toInstant();

        reportRepository.streamTransactionsInPeriod(
                fromInstant, toExclusiveInstant, type, maxRows, rowConsumer);
    }

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
     * Computes a chronological time-series trend of transaction volume, fee revenue, and closing
     * balance (either system-wide or for a specific account) across all buckets in {@code [from,
     * to]} (L3-08, FR-20, ADR-013).
     *
     * @param granularity bucket granularity ({@code DAY}, {@code WEEK}, or {@code MONTH})
     * @param from inclusive start date in business timezone
     * @param to inclusive end date in business timezone
     * @param accountId optional account ID; when {@code null}, returns system-wide trends
     * @return chronological list of trend points
     */
    @Transactional(readOnly = true)
    public List<TrendPointResponse> getTrendSeries(
            TrendGranularity granularity, LocalDate from, LocalDate to, Long accountId) {
        if (granularity == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Trend granularity is required");
        }
        validateDateRange(from, to);

        ZoneId businessZone = clock.getZone();
        Instant fromInstant = from.atStartOfDay(businessZone).toInstant();
        Instant toExclusiveInstant = to.plusDays(1).atStartOfDay(businessZone).toInstant();

        if (accountId != null) {
            if (!reportRepository.existsAccountById(accountId)) {
                throw new ResourceNotFoundException("Account", accountId);
            }
            return reportRepository.findAccountTrendSeries(
                    granularity,
                    accountId,
                    from,
                    to,
                    fromInstant,
                    toExclusiveInstant,
                    businessZone);
        }

        return reportRepository.findSystemTrendSeries(
                granularity, from, to, fromInstant, toExclusiveInstant, businessZone);
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
