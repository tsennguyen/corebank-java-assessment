package com.example.corebanking.recurring.api.dto;

import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request payload for creating a scheduled recurring transaction instruction (L2-06, FR-16). */
@Schema(description = "Yêu cầu thiết lập giao dịch định kỳ")
public record CreateRecurringTransactionRequest(
        @Schema(
                        description = "Loại giao dịch định kỳ (DEPOSIT, PAYMENT, TRANSFER)",
                        example = "PAYMENT",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Recurring transaction type is required")
                RecurringTransactionType type,
        @Schema(
                        description = "ID tài khoản nguồn (bắt buộc với PAYMENT và TRANSFER)",
                        example = "1")
                Long sourceAccountId,
        @Schema(description = "ID tài khoản đích (bắt buộc với DEPOSIT và TRANSFER)", example = "2")
                Long targetAccountId,
        @Schema(
                        description = "Số tiền giao dịch mỗi kỳ (VND)",
                        example = "5000000.00",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Amount is required")
                @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message = "Amount must have at most 17 integer digits and 2 decimal places")
                BigDecimal amount,
        @Schema(
                        description = "Tần suất thực thi (DAILY, WEEKLY, MONTHLY)",
                        example = "MONTHLY",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Frequency is required")
                RecurringFrequency frequency,
        @Schema(
                        description = "Ngày bắt đầu kỳ đầu tiên (YYYY-MM-DD, không ở quá khứ)",
                        example = "2026-10-15",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotNull(message = "Start date is required")
                LocalDate startDate,
        @Schema(
                        description =
                                "Ngày kết thúc tùy chọn (YYYY-MM-DD, phải >= startDate nếu có)",
                        example = "2027-10-15")
                LocalDate endDate,
        @Schema(description = "Địa điểm/kênh giao dịch tùy chọn", example = "Hà Nội")
                @Size(max = 255, message = "Location must not exceed 255 characters")
                String location,
        @Schema(
                        description = "Nội dung diễn giải giao dịch định kỳ",
                        example = "Thanh toán hóa đơn định kỳ hàng tháng")
                @Size(max = 500, message = "Description must not exceed 500 characters")
                String description) {}
