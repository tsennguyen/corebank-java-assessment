package com.example.corebanking.report.export;

import com.example.corebanking.report.api.dto.ReportBundle;
import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.application.ReportDataService;
import com.example.corebanking.report.domain.ExportFormat;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Abstract base class implementing the <b>Template Method Pattern</b> for financial report exports
 * (Excel {@code .xlsx} and PDF {@code .pdf}) (L3-07, L3-08, G-05, FR-21, ADR-014, Architecture
 * §3.12).
 *
 * <p>Subclasses provide format-specific rendering steps while sharing parallel data loading on
 * Virtual Threads ({@link ReportDataService#loadReportBundle}) and headless chart generation
 * ({@link ChartRenderer#renderAllCharts}). Per-export state is isolated in a request-scoped context
 * {@code C} so singleton Spring beans remain strictly thread-safe under concurrent exports.
 *
 * @param <C> per-export document/workbook context type
 */
public abstract class ReportExporter<C> {

    /** Standardized report header title strictly compliant with E-02 (no real bank names). */
    public static final String REPORT_TITLE = "Core Banking System — Báo cáo tài chính";

    private final ReportDataService reportDataService;
    private final ChartRenderer chartRenderer;

    protected ReportExporter(ReportDataService reportDataService, ChartRenderer chartRenderer) {
        this.reportDataService = reportDataService;
        this.chartRenderer = chartRenderer;
    }

    /**
     * Returns the {@link ExportFormat} handled by this exporter implementation.
     *
     * @return supported export format
     */
    public abstract ExportFormat supportedFormat();

    /**
     * Template Method orchestrating the invariant 5-step report generation workflow:
     *
     * <ol>
     *   <li>Load periodic summary buckets and trend points in parallel on Virtual Threads, then
     *       render the 3 trend charts as 800x400 PNG images.
     *   <li>Create context and write the report header ({@link #writeHeader}).
     *   <li>Write the periodic summary table ({@link #writeSummary}).
     *   <li>Embed the 3 trend charts ({@link #writeCharts}).
     *   <li>Stream transaction detail rows ({@link #writeDetails}) and flush output ({@link
     *       #finish}), guaranteeing temporary resource disposal in {@code finally} ({@link
     *       #cleanup}).
     * </ol>
     *
     * @param criteria validated report export criteria
     * @param outputStream target output stream
     * @throws IOException if writing to the output stream fails
     */
    public final void export(ReportExportCriteria criteria, OutputStream outputStream)
            throws IOException {
        ReportBundle bundle =
                reportDataService.loadReportBundle(
                        criteria.period(), criteria.from(), criteria.to(), criteria.type());
        ChartRenderer.RenderedCharts charts = chartRenderer.renderAllCharts(bundle.trendPoints());

        C context = createContext(criteria, outputStream);
        try {
            writeHeader(context, criteria);
            writeSummary(context, criteria, bundle.periodicBuckets());
            writeCharts(context, charts);
            writeDetails(context, criteria, reportDataService);
            finish(context, outputStream);
        } finally {
            cleanup(context);
        }
    }

    protected abstract C createContext(ReportExportCriteria criteria, OutputStream outputStream)
            throws IOException;

    protected abstract void writeHeader(C context, ReportExportCriteria criteria)
            throws IOException;

    protected abstract void writeSummary(
            C context,
            ReportExportCriteria criteria,
            List<TransactionReportBucketResponse> periodicBuckets)
            throws IOException;

    protected abstract void writeCharts(C context, ChartRenderer.RenderedCharts charts)
            throws IOException;

    protected abstract void writeDetails(
            C context, ReportExportCriteria criteria, ReportDataService dataService)
            throws IOException;

    protected abstract void finish(C context, OutputStream outputStream) throws IOException;

    protected abstract void cleanup(C context);
}
