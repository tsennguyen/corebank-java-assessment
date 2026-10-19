package com.example.corebanking.transaction.api.dto;

import com.example.corebanking.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request DTO for previewing transaction fees before execution. */
@Schema(description = "Yêu cầu xem trước phí giao dịch")
public record FeePreviewRequest(
        @Schema(
                        description = "Loại giao dịch cần xem phí",
                        example = "WITHDRAWAL",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Transaction type is required")
                TransactionType type,
        @Schema(
                        description = "Số tiền giao dịch dự kiến",
                        example = "12000000.00",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Amount is required")
                @DecimalMin(value = "0.01", message = "Amount must be strictly positive")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message = "Amount cannot have more than 2 decimal places")
                BigDecimal amount) {}
