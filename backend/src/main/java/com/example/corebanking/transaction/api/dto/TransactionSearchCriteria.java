package com.example.corebanking.transaction.api.dto;

import com.example.corebanking.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

/** Criteria object for filtering transactions. */
@Schema(description = "Tiêu chí tìm kiếm và lọc giao dịch")
public record TransactionSearchCriteria(
        @Schema(description = "Lọc theo ID tài khoản (nguồn hoặc đích)", example = "1")
                Long accountId,
        @Schema(description = "Lọc theo loại giao dịch", example = "DEPOSIT")
                TransactionType type) {}
