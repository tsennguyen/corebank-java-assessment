package com.example.corebanking.report.api.dto;

import java.util.List;

/**
 * Aggregated report data bundle combining periodic transaction summary buckets (FR-19) and
 * time-series trend points (FR-20) loaded in parallel on Virtual Threads for financial report
 * exports (L3-07, L3-08, L3-10, Architecture §3.12).
 *
 * @param periodicBuckets aggregated periodic transaction buckets
 * @param trendPoints chronological trend points for chart rendering
 */
public record ReportBundle(
        List<TransactionReportBucketResponse> periodicBuckets,
        List<TrendPointResponse> trendPoints) {}
