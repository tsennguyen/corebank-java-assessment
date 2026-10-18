package com.example.corebanking.report.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable time-series point representing transaction volume, fee revenue, and closing balance for
 * a single time bucket (L3-08, FR-20, ADR-013).
 *
 * @param bucket start date of the bucket in the business timezone ({@code Asia/Ho_Chi_Minh})
 * @param txCount number of completed non-reversal transactions in the bucket
 * @param totalAmount sum of completed transaction amounts in the bucket (scale 2)
 * @param totalFee sum of transaction fees collected in the bucket (scale 2)
 * @param closingBalance reconstructed closing balance at the end of the bucket (system-wide or
 *     single-account, scale 2)
 */
@Schema(description = "Time-series trend bucket for transaction volume, fee, and closing balance")
public record TrendPointResponse(
        @Schema(
                        description = "Start date of the time bucket (Asia/Ho_Chi_Minh)",
                        example = "2026-10-01")
                LocalDate bucket,
        @Schema(
                        description = "Number of completed business transactions in the bucket",
                        example = "12")
                long txCount,
        @Schema(description = "Total transaction amount in VND (scale 2)", example = "45000000.00")
                BigDecimal totalAmount,
        @Schema(description = "Total transaction fee in VND (scale 2)", example = "18000.00")
                BigDecimal totalFee,
        @Schema(
                        description = "Closing balance at the end of the bucket in VND (scale 2)",
                        example = "1250000000.00")
                BigDecimal closingBalance) {}
