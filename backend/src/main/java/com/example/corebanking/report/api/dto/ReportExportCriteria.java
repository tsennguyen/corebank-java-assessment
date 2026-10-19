package com.example.corebanking.report.api.dto;

import com.example.corebanking.report.domain.ExportFormat;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.transaction.domain.TransactionType;
import java.time.LocalDate;
import java.util.Locale;

/**
 * Validated criteria for exporting a periodic financial report to Excel or PDF (L3-07, L3-08,
 * FR-21).
 *
 * @param format target export file format ({@code XLSX} or {@code PDF})
 * @param period aggregation period unit ({@code WEEK}, {@code QUARTER}, {@code YEAR})
 * @param from inclusive start date in business timezone
 * @param to inclusive end date in business timezone
 * @param type optional transaction type filter
 */
public record ReportExportCriteria(
        ExportFormat format,
        ReportPeriod period,
        LocalDate from,
        LocalDate to,
        TransactionType type) {

    /**
     * Builds the standardized attachment filename: {@code
     * core-banking-report-<period>-<from>-<to>.<ext>} (AC 1, E-02).
     *
     * @return attachment filename
     */
    public String buildFilename() {
        return "core-banking-report-"
                + period.name().toLowerCase(Locale.ROOT)
                + "-"
                + from
                + "-"
                + to
                + "."
                + format.fileExtension();
    }
}
