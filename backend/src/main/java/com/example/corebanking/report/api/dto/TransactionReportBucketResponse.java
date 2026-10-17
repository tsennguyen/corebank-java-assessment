package com.example.corebanking.report.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO representing one aggregated time bucket (week, quarter, or year) in a periodic
 * transaction report (L3-01, L3-02, FR-19).
 */
@Schema(description = "Aggregated transaction statistics for a single report period bucket")
public record TransactionReportBucketResponse(
        @Schema(
                        description =
                                "Start date of the period bucket in Asia/Ho_Chi_Minh timezone"
                                        + " (Monday for WEEK, first day of quarter for QUARTER,"
                                        + " Jan 1 for YEAR)",
                        example = "2026-04-01")
                LocalDate periodStart,
        @Schema(
                        description =
                                "Number of completed business transactions in the bucket"
                                        + " (excluding REVERSED and REVERSAL)",
                        example = "12")
                long txCount,
        @Schema(
                        description = "Sum of transaction amounts in VND (scale 2)",
                        example = "125000000.00")
                BigDecimal totalAmount,
        @Schema(
                        description = "Arithmetic mean of transaction amounts in VND (scale 2)",
                        example = "10416666.67")
                BigDecimal averageAmount,
        @Schema(
                        description = "Maximum single transaction amount in VND (scale 2)",
                        example = "50000000.00")
                BigDecimal maxAmount,
        @Schema(
                        description = "Minimum single transaction amount in VND (scale 2)",
                        example = "500000.00")
                BigDecimal minAmount,
        @Schema(
                        description = "Sum of transaction fees collected in VND (scale 2)",
                        example = "24000.00")
                BigDecimal totalFee) {}
