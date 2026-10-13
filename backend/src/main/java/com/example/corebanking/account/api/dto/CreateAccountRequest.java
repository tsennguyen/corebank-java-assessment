package com.example.corebanking.account.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request payload for opening a new bank account. */
@Schema(description = "Request payload for opening a new account")
public record CreateAccountRequest(
        @Schema(description = "ID of the customer opening the account", example = "1")
                @NotNull(message = "Customer ID is required")
                Long customerId,
        @Schema(description = "Per-transaction spending limit", example = "50000000.00")
                @NotNull(message = "Transaction limit is required")
                @DecimalMin(value = "0.01", message = "Transaction limit must be greater than zero")
                BigDecimal transactionLimit,
        @Schema(description = "Daily aggregate spending limit", example = "100000000.00")
                @NotNull(message = "Daily limit is required")
                @DecimalMin(value = "0.01", message = "Daily limit must be greater than zero")
                BigDecimal dailyLimit) {}
