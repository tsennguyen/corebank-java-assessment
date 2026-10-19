package com.example.corebanking.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request payload for modifying account transaction limits. */
@Schema(description = "Request payload for updating account transaction limits")
public record UpdateAccountLimitsRequest(
        @Schema(description = "Per-transaction spending limit", example = "60000000.00")
                @NotNull(message = "Transaction limit is required")
                @DecimalMin(value = "0.01", message = "Transaction limit must be greater than zero")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message =
                                "Transaction limit must have at most 17 integer digits and 2"
                                        + " decimal places")
                BigDecimal transactionLimit,
        @Schema(description = "Daily aggregate spending limit", example = "120000000.00")
                @NotNull(message = "Daily limit is required")
                @DecimalMin(value = "0.01", message = "Daily limit must be greater than zero")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message =
                                "Daily limit must have at most 17 integer digits and 2 decimal"
                                        + " places")
                BigDecimal dailyLimit,
        @Schema(
                        description = "Current account version for optimistic concurrency control",
                        example = "0")
                @NotNull(message = "Version is required for optimistic locking concurrency control")
                Long version) {}
