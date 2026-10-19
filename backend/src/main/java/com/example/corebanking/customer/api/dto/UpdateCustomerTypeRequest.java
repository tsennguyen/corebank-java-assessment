package com.example.corebanking.customer.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request DTO for updating a CustomerType's maximum transaction and daily limits (L3-05, L3-06).
 */
@Schema(description = "Yêu cầu cập nhật hạn mức tối đa cho loại khách hàng")
public record UpdateCustomerTypeRequest(
        @Schema(description = "Tên hiển thị loại khách hàng (tùy chọn)", example = "Cá nhân")
                @Size(max = 100, message = "Customer type name must not exceed 100 characters")
                String name,
        @Schema(
                        description = "Hạn mức tối đa trên mỗi giao dịch (VND, > 0)",
                        example = "150000000.00")
                @NotNull(message = "Max transaction limit is required")
                @DecimalMin(
                        value = "0.01",
                        message = "Max transaction limit must be greater than zero")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message =
                                "Max transaction limit must have at most 17 integer digits and 2"
                                        + " decimal places")
                BigDecimal maxTransactionLimit,
        @Schema(
                        description =
                                "Hạn mức giao dịch tối đa trong ngày (VND, >= maxTransactionLimit)",
                        example = "600000000.00")
                @NotNull(message = "Max daily limit is required")
                @DecimalMin(value = "0.01", message = "Max daily limit must be greater than zero")
                @Digits(
                        integer = 17,
                        fraction = 2,
                        message =
                                "Max daily limit must have at most 17 integer digits and 2 decimal"
                                        + " places")
                BigDecimal maxDailyLimit,
        @Schema(
                        description =
                                "Phiên bản bản ghi hiện tại để kiểm soát khóa lạc quan (tùy chọn)",
                        example = "0")
                Long version) {

    /** Convenience constructor when updating limits without changing the display name. */
    public UpdateCustomerTypeRequest(BigDecimal maxTransactionLimit, BigDecimal maxDailyLimit) {
        this(null, maxTransactionLimit, maxDailyLimit, null);
    }

    /** Convenience constructor when updating name and limits without explicit version. */
    public UpdateCustomerTypeRequest(
            String name, BigDecimal maxTransactionLimit, BigDecimal maxDailyLimit) {
        this(name, maxTransactionLimit, maxDailyLimit, null);
    }
}
