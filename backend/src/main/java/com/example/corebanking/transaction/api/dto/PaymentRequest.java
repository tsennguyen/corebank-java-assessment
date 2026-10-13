package com.example.corebanking.transaction.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request DTO for executing an outbound merchant or service payment. */
@Schema(description = "Yêu cầu thanh toán hóa đơn hoặc dịch vụ")
public record PaymentRequest(
        @Schema(
                description = "ID tài khoản nguồn trích nợ",
                example = "1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Source account ID is required")
        Long sourceAccountId,
        @Schema(
                description = "Số tiền thanh toán (phải lớn hơn 0 và tối đa 2 chữ số thập phân)",
                example = "500000.00",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be strictly positive")
        @Digits(
                integer = 15,
                fraction = 2,
                message = "Amount cannot have more than 2 decimal places")
        BigDecimal amount,
        @Schema(description = "Địa điểm thực hiện giao dịch", example = "Online Gateway")
        @Size(max = 255, message = "Location must not exceed 255 characters")
        String location,
        @Schema(description = "Nội dung thanh toán", example = "Thanh toán cước Internet")
        @Size(max = 500, message = "Description must not exceed 500 characters")
        String description,
        @Schema(description = "Thông tin đơn vị thụ hưởng", example = "VNPT Telecom")
        @Size(max = 255, message = "Recipient info must not exceed 255 characters")
        String recipientInfo) {}
