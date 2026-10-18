package com.example.corebanking.report.application;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.report.api.dto.ReportBundle;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.transaction.domain.TransactionType;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Orchestration service loading periodic transaction summaries and time-series trend data in
 * parallel on Java 21 Virtual Threads via {@link CompletableFuture} (AC 4, L3-08, L3-10, NFR-4,
 * ADR-015, Architecture §3.12).
 */
@Service
public class ReportDataService {

    private final ReportService reportService;
    private final Executor virtualThreadExecutor;

    public ReportDataService(
            ReportService reportService,
            @Qualifier("applicationTaskExecutor") Executor virtualThreadExecutor) {
        this.reportService = reportService;
        this.virtualThreadExecutor = virtualThreadExecutor;
    }

    /**
     * Retrieves the trend series for the requested granularity, date range, and optional account.
     *
     * @param granularity bucket granularity ({@code DAY}, {@code WEEK}, or {@code MONTH})
     * @param from inclusive start date
     * @param to inclusive end date
     * @param accountId optional account ID filter
     * @return chronological list of trend points
     */
    public List<TrendPointResponse> getTrendSeries(
            TrendGranularity granularity, LocalDate from, LocalDate to, Long accountId) {
        return reportService.getTrendSeries(granularity, from, to, accountId);
    }

    /**
     * Loads the periodic transaction summary and the corresponding trend series concurrently on
     * Virtual Threads using the default trend granularity mapped from {@code period}.
     *
     * @param period report period ({@code WEEK}, {@code QUARTER}, {@code YEAR})
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter
     * @return {@link ReportBundle} containing periodic buckets and trend points
     */
    public ReportBundle loadReportBundle(
            ReportPeriod period, LocalDate from, LocalDate to, TransactionType type) {
        return loadReportBundle(period, defaultGranularityFor(period), from, to, type, null);
    }

    /**
     * Loads the periodic transaction summary and trend series concurrently on Virtual Threads via
     * {@link CompletableFuture#supplyAsync}.
     *
     * @param period report period ({@code WEEK}, {@code QUARTER}, {@code YEAR})
     * @param granularity trend bucket granularity ({@code DAY}, {@code WEEK}, {@code MONTH})
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter
     * @param accountId optional account ID for single-account balance trends
     * @return {@link ReportBundle} containing periodic buckets and trend points
     */
    public ReportBundle loadReportBundle(
            ReportPeriod period,
            TrendGranularity granularity,
            LocalDate from,
            LocalDate to,
            TransactionType type,
            Long accountId) {
        if (period == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Report period is required");
        }
        if (granularity == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Trend granularity is required");
        }
        reportService.validateDateRange(from, to);

        CompletableFuture<List<TransactionReportBucketResponse>> periodicFuture =
                CompletableFuture.supplyAsync(
                        () -> reportService.getPeriodicTransactionReport(period, from, to, type),
                        virtualThreadExecutor);

        CompletableFuture<List<TrendPointResponse>> trendsFuture =
                CompletableFuture.supplyAsync(
                        () -> reportService.getTrendSeries(granularity, from, to, accountId),
                        virtualThreadExecutor);

        try {
            CompletableFuture.allOf(periodicFuture, trendsFuture).join();
            return new ReportBundle(periodicFuture.join(), trendsFuture.join());
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            throw ex;
        }
    }

    /**
     * Maps a {@link ReportPeriod} to its natural chart {@link TrendGranularity} ({@code WEEK ->
     * DAY}, {@code QUARTER -> WEEK}, {@code YEAR -> MONTH}).
     */
    public static TrendGranularity defaultGranularityFor(ReportPeriod period) {
        if (period == null) {
            return TrendGranularity.DAY;
        }
        return switch (period) {
            case WEEK -> TrendGranularity.DAY;
            case QUARTER -> TrendGranularity.WEEK;
            case YEAR -> TrendGranularity.MONTH;
        };
    }
}
