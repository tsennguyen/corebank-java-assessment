package com.example.corebanking.transaction.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request payload for depositing money into a bank account. */
@Schema(description = "Payload yêu cầu nạp tiền vào tài khoản")
public record DepositRequest(
        @Schema(description = "ID của tài khoản nhận tiền", example = "1")
                @NotNull(message = "Account ID is required")
                Long accountId,
        @Schema(
                        description = "Số tiền nạp (lớn hơn 0, tối đa 2 chữ số thập phân)",
                        example = "1500000.00")
                @NotNull(message = "Amount is required")
                @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message = "Amount must have at most 2 decimal places")
                BigDecimal amount,
        @Schema(description = "Địa điểm giao dịch (chi nhánh hoặc tỉnh/thành)", example = "Hà Nội")
                @Size(max = 255, message = "Location must not exceed 255 characters")
                String location,
        @Schema(description = "Nội dung giao dịch", example = "Nạp tiền ban đầu")
                @Size(max = 500, message = "Description must not exceed 500 characters")
                String description) {}
