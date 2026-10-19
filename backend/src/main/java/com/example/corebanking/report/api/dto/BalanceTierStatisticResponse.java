package com.example.corebanking.report.api.dto;

import com.example.corebanking.account.domain.BalanceTier;
import io.swagger.v3.oas.annotations.media.Schema;

/** Response DTO representing account and transaction statistics for a single balance tier. */
@Schema(description = "Aggregated account and transaction statistics for a balance tier")
public record BalanceTierStatisticResponse(
        @Schema(description = "Balance tier classification (LOW, MEDIUM, HIGH)", example = "MEDIUM")
                BalanceTier tier,
        @Schema(description = "Number of non-closed accounts in this tier", example = "12")
                long accountCount,
        @Schema(description = "Total transactions involving accounts in this tier", example = "48")
                long transactionCount) {}
