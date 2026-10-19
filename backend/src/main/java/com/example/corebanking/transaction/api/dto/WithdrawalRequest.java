package com.example.corebanking.transaction.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request DTO for executing a cash withdrawal from an account. */
@Schema(description = "Yêu cầu rút tiền từ tài khoản")
public record WithdrawalRequest(
        @Schema(
                        description = "ID tài khoản nguồn",
                        example = "1",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Account ID is required")
                Long accountId,
        @Schema(
                        description = "Số tiền rút (phải lớn hơn 0 và tối đa 2 chữ số thập phân)",
                        example = "12000000.00",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Amount is required")
                @DecimalMin(value = "0.01", message = "Amount must be strictly positive")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message = "Amount cannot have more than 2 decimal places")
                BigDecimal amount,
        @Schema(description = "Địa điểm thực hiện giao dịch", example = "Chi nhánh Hà Nội")
                @Size(max = 255, message = "Location must not exceed 255 characters")
                String location,
        @Schema(description = "Nội dung giao dịch", example = "Rút tiền mặt tại quầy")
                @Size(max = 500, message = "Description must not exceed 500 characters")
                String description) {}
