package com.example.corebanking.transaction.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request DTO for executing a fund transfer between two distinct accounts. */
@Schema(description = "Yêu cầu chuyển khoản nội bộ giữa hai tài khoản")
public record TransferRequest(
        @Schema(
                description = "ID tài khoản nguồn trích nợ",
                example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Source account ID is required")
        Long sourceAccountId,
        @Schema(
                description = "ID tài khoản đích nhận tiền",
                example = "2",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Target account ID is required")
        Long targetAccountId,
        @Schema(
                description = "Số tiền chuyển (phải lớn hơn 0 và tối đa 2 chữ số thập phân)",
                example = "5000000.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be strictly positive")
        @Digits(
                integer = 15,
                fraction = 2,
                message = "Amount cannot have more than 2 decimal places")
        BigDecimal amount,
        @Schema(description = "Địa điểm thực hiện giao dịch", example = "Mobile Banking")
        @Size(max = 255, message = "Location must not exceed 255 characters")
        String location,
        @Schema(description = "Nội dung chuyển tiền", example = "Chuyển tiền mua sắm")
        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description) {}
