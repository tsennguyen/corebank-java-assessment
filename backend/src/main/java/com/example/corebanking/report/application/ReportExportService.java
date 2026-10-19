package com.example.corebanking.report.application;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.domain.ExportFormat;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.export.ReportExporter;
import com.example.corebanking.transaction.domain.TransactionType;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Application service validating export requests before HTTP headers are committed and dispatching
 * to the appropriate {@link ReportExporter} implementation (L3-07, L3-08, G-05, FR-21, ADR-014).
 */
@Service
public class ReportExportService {

    private final ReportService reportService;
    private final Map<ExportFormat, ReportExporter<?>> exportersByFormat;

    public ReportExportService(
            ReportService reportService, List<ReportExporter<?>> reportExporters) {
        this.reportService = reportService;
        this.exportersByFormat = new EnumMap<>(ExportFormat.class);
        if (reportExporters != null) {
            for (ReportExporter<?> exporter : reportExporters) {
                this.exportersByFormat.put(exporter.supportedFormat(), exporter);
            }
        }
    }

    /**
     * Validates all export parameters synchronously <b>before</b> {@code StreamingResponseBody}
     * commits the HTTP 200 status and attachment headers, ensuring invalid requests receive an RFC
     * 9457 {@code 400 ProblemDetail} JSON response (AC 3, NC-08).
     *
     * @param format target export format ({@code XLSX} or {@code PDF})
     * @param period aggregation period ({@code WEEK}, {@code QUARTER}, {@code YEAR})
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter
     * @return validated {@link ReportExportCriteria}
     */
    public ReportExportCriteria validateAndBuildCriteria(
            ExportFormat format,
            ReportPeriod period,
            LocalDate from,
            LocalDate to,
            TransactionType type) {
        if (format == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Export format is required");
        }
        if (period == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Report period is required");
        }
        reportService.validateDateRange(from, to);
        if (type == TransactionType.REVERSAL) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "REVERSAL transactions are excluded from financial report exports and cannot be"
                            + " used as a filter");
        }
        if (!exportersByFormat.containsKey(format)) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Unsupported export format: " + format);
        }
        return new ReportExportCriteria(format, period, from, to, type);
    }

    /**
     * Executes the streaming export for the given validated criteria into {@code outputStream}.
     *
     * @param criteria validated export criteria
     * @param outputStream target HTTP response output stream
     * @throws IOException if writing to the output stream fails
     */
    public void export(ReportExportCriteria criteria, OutputStream outputStream)
            throws IOException {
        ReportExporter<?> exporter = exportersByFormat.get(criteria.format());
        if (exporter == null) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED, "Unsupported export format: " + criteria.format());
        }
        exporter.export(criteria, outputStream);
    }
}
