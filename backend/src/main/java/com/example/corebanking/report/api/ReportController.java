package com.example.corebanking.report.api;

import com.example.corebanking.report.api.dto.ReportExportCriteria;
import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.api.dto.TrendPointResponse;
import com.example.corebanking.report.application.ReportExportService;
import com.example.corebanking.report.application.ReportService;
import com.example.corebanking.report.domain.ExportFormat;
import com.example.corebanking.report.domain.ReportPeriod;
import com.example.corebanking.report.domain.TrendGranularity;
import com.example.corebanking.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * REST controller exposing periodic transaction analytics reports, time-series trends, and
 * streaming financial report exports (L3-01, L3-02, L3-07, L3-08, G-05, FR-19, FR-20, FR-21).
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(
        name = "Reports",
        description = "Periodic transaction analytics, trend series, and financial report exports")
public class ReportController {

    private final ReportService reportService;
    private final ReportExportService reportExportService;

    /**
     * Retrieves aggregated transaction metrics grouped by {@code WEEK}, {@code QUARTER}, or {@code
     * YEAR} in the Vietnam business timezone ({@code Asia/Ho_Chi_Minh}).
     *
     * @param period aggregation period unit
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter
     * @return chronological list of periodic report buckets
     */
    @GetMapping("/transactions")
    @Operation(
            summary = "Get periodic transaction volume report",
            description =
                    "Aggregates completed business transactions by WEEK, QUARTER, or YEAR in the"
                            + " Vietnam business timezone (Asia/Ho_Chi_Minh), returning transaction"
                            + " count, total, average, maximum, minimum amount, and total fee per"
                            + " period bucket (L3-01, L3-02, FR-19).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Periodic transaction report retrieved successfully"),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Invalid period, missing dates, 'to' before 'from', or range > 366 days",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Access denied (requires ADMIN role)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<List<TransactionReportBucketResponse>> getPeriodicTransactionReport(
            @Parameter(
                            description = "Aggregation period unit (WEEK, QUARTER, YEAR)",
                            example = "QUARTER",
                            required = true)
                    @RequestParam("period")
                    ReportPeriod period,
            @Parameter(
                            description = "Inclusive start date (yyyy-MM-dd, Asia/Ho_Chi_Minh)",
                            example = "2026-01-01",
                            required = true)
                    @RequestParam("from")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(
                            description = "Inclusive end date (yyyy-MM-dd, Asia/Ho_Chi_Minh)",
                            example = "2026-06-30",
                            required = true)
                    @RequestParam("to")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(
                            description =
                                    "Optional transaction type filter (DEPOSIT, WITHDRAWAL,"
                                            + " TRANSFER, PAYMENT)",
                            example = "TRANSFER")
                    @RequestParam(value = "type", required = false)
                    TransactionType type) {
        return ResponseEntity.ok(
                reportService.getPeriodicTransactionReport(period, from, to, type));
    }

    /**
     * Retrieves chronological trend series of transaction volume, fee revenue, and closing balance
     * (system-wide or single-account) grouped by {@code DAY}, {@code WEEK}, or {@code MONTH}
     * (L3-08, FR-20).
     *
     * @param granularity bucket granularity ({@code DAY}, {@code WEEK}, {@code MONTH})
     * @param from inclusive start date
     * @param to inclusive end date
     * @param accountId optional account ID for single-account balance trends
     * @return chronological list of trend points
     */
    @GetMapping("/trends")
    @Operation(
            summary = "Get transaction volume, fee, and closing balance trend series",
            description =
                    "Returns continuous time-series trend buckets (DAY, WEEK, or MONTH) with"
                            + " transaction count, total amount, total fee, and reconstructed"
                            + " closing balance (system-wide using system_balance_delta window"
                            + " function, or single-account using last balance_after per bucket"
                            + " with forward-fill) (L3-08, FR-20, ADR-013).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Trend series retrieved successfully"),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Invalid granularity, missing dates, 'to' before 'from', or range > 366"
                                + " days",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Access denied (requires ADMIN role)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Specified accountId not found",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<List<TrendPointResponse>> getTrendSeries(
            @Parameter(
                            description = "Bucket granularity (DAY, WEEK, MONTH)",
                            example = "DAY",
                            required = true)
                    @RequestParam("granularity")
                    TrendGranularity granularity,
            @Parameter(
                            description = "Inclusive start date (yyyy-MM-dd, Asia/Ho_Chi_Minh)",
                            example = "2026-10-01",
                            required = true)
                    @RequestParam("from")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(
                            description = "Inclusive end date (yyyy-MM-dd, Asia/Ho_Chi_Minh)",
                            example = "2026-10-14",
                            required = true)
                    @RequestParam("to")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(
                            description =
                                    "Optional account ID; when omitted, returns system-wide"
                                            + " closing balances",
                            example = "1")
                    @RequestParam(value = "accountId", required = false)
                    Long accountId) {
        return ResponseEntity.ok(reportService.getTrendSeries(granularity, from, to, accountId));
    }

    /**
     * Streams a financial report in Excel ({@code .xlsx}) or PDF ({@code .pdf}) format containing
     * periodic summary metrics, embedded trend charts, and transaction details (L3-07, L3-08, G-05,
     * FR-21, NFR-2, ADR-014).
     *
     * @param format export file format ({@code XLSX} or {@code PDF})
     * @param period aggregation period ({@code WEEK}, {@code QUARTER}, {@code YEAR})
     * @param from inclusive start date
     * @param to inclusive end date
     * @param type optional transaction type filter
     * @return streaming attachment response
     */
    @GetMapping("/export")
    @Operation(
            summary = "Export financial report to Excel (.xlsx) or PDF (.pdf)",
            description =
                    "Streams a complete financial report with periodic summary metrics, three"
                            + " embedded 800x400 trend charts (volume, balance, fee), and"
                            + " transaction details using SXSSFWorkbook(100) + JDBC cursor"
                            + " streaming (fetchSize=1000) to bound memory usage (L3-07, L3-08,"
                            + " G-05, FR-21, NFR-2, ADR-014).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Financial report streamed as file attachment"),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Invalid format/period, missing dates, 'to' before 'from', or range >"
                                + " 366 days",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Access denied (requires ADMIN role)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<StreamingResponseBody> exportReport(
            @Parameter(
                            description = "Export file format (XLSX, PDF)",
                            example = "XLSX",
                            required = true)
                    @RequestParam("format")
                    ExportFormat format,
            @Parameter(
                            description = "Aggregation period unit (WEEK, QUARTER, YEAR)",
                            example = "QUARTER",
                            required = true)
                    @RequestParam("period")
                    ReportPeriod period,
            @Parameter(
                            description = "Inclusive start date (yyyy-MM-dd, Asia/Ho_Chi_Minh)",
                            example = "2026-01-01",
                            required = true)
                    @RequestParam("from")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @Parameter(
                            description = "Inclusive end date (yyyy-MM-dd, Asia/Ho_Chi_Minh)",
                            example = "2026-06-30",
                            required = true)
                    @RequestParam("to")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to,
            @Parameter(
                            description =
                                    "Optional transaction type filter (DEPOSIT, WITHDRAWAL,"
                                            + " TRANSFER, PAYMENT)",
                            example = "TRANSFER")
                    @RequestParam(value = "type", required = false)
                    TransactionType type) {
        ReportExportCriteria criteria =
                reportExportService.validateAndBuildCriteria(format, period, from, to, type);
        StreamingResponseBody body =
                outputStream -> reportExportService.export(criteria, outputStream);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + criteria.buildFilename() + "\"")
                .contentType(MediaType.parseMediaType(criteria.format().contentType()))
                .body(body);
    }
}
