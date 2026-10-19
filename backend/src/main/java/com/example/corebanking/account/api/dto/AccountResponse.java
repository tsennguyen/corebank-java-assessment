package com.example.corebanking.account.api.dto;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.BalanceTier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Account details representation including balance tier and limits. */
@Schema(description = "Account details response")
public record AccountResponse(
        @Schema(description = "Internal account ID", example = "1") Long id,
        @Schema(
                        description = "12-digit unique account number with Luhn check digit",
                        example = "100000000016")
                String accountNumber,
        @Schema(description = "Associated customer ID", example = "1") Long customerId,
        @Schema(description = "Currency code", example = "VND") String currency,
        @Schema(description = "Current account balance", example = "0.00") BigDecimal balance,
        @Schema(description = "Per-transaction limit", example = "50000000.00")
                BigDecimal transactionLimit,
        @Schema(description = "Daily aggregate transaction limit", example = "100000000.00")
                BigDecimal dailyLimit,
        @Schema(description = "Date account was opened", example = "2026-10-09")
                LocalDate openedDate,
        @Schema(description = "Lifecycle status", example = "ACTIVE") AccountStatus status,
        @Schema(description = "Categorized balance tier", example = "LOW") BalanceTier balanceTier,
        @Schema(description = "Timestamp when account was closed") Instant closedAt,
        @Schema(description = "Creation timestamp") Instant createdAt,
        @Schema(description = "Last update timestamp") Instant updatedAt,
        @Schema(description = "Current entity version for optimistic locking", example = "0")
                Long version) {

    public static AccountResponse from(Account account, BalanceTier balanceTier) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerId(),
                account.getCurrency(),
                account.getBalance(),
                account.getTransactionLimit(),
                account.getDailyLimit(),
                account.getOpenedDate(),
                account.getStatus(),
                balanceTier,
                account.getClosedAt(),
                account.getCreatedAt(),
                account.getUpdatedAt(),
                account.getVersion());
    }
}
