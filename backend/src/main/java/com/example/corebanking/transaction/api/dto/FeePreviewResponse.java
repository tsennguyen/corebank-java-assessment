package com.example.corebanking.transaction.api.dto;

import com.example.corebanking.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** Response representation of a fee calculation preview. */
@Schema(description = "Kết quả tính phí xem trước")
public record FeePreviewResponse(
        @Schema(description = "Loại giao dịch", example = "WITHDRAWAL") TransactionType type,
        @Schema(description = "Số tiền giao dịch", example = "12000000.00") BigDecimal amount,
        @Schema(description = "Số tiền phí tính được", example = "2400.00") BigDecimal fee) {}
