package com.example.corebanking.report.api;

import com.example.corebanking.report.api.dto.TransactionReportBucketResponse;
import com.example.corebanking.report.application.ReportService;
import com.example.corebanking.report.domain.ReportPeriod;
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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller exposing periodic transaction analytics reports (L3-01, L3-02, G-05, FR-19). */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(
        name = "Reports",
        description = "Periodic transaction analytics, trend series, and financial report exports")
public class ReportController {

    private final ReportService reportService;

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
}
