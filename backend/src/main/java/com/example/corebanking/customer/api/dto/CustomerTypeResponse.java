package com.example.corebanking.customer.api.dto;

import com.example.corebanking.customer.domain.CustomerType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response DTO representing a CustomerType classification and its maximum limits (L3-05, L3-06).
 */
@Schema(description = "Thông tin phân loại khách hàng và hạn mức giao dịch tối đa theo loại")
public record CustomerTypeResponse(
        @Schema(description = "ID nội bộ của loại khách hàng", example = "1") Long id,
        @Schema(description = "Mã loại khách hàng", example = "INDIVIDUAL") String code,
        @Schema(description = "Tên hiển thị loại khách hàng", example = "Cá nhân") String name,
        @Schema(description = "Hạn mức tối đa trên mỗi giao dịch (VND)", example = "100000000.00")
                BigDecimal maxTransactionLimit,
        @Schema(description = "Hạn mức giao dịch tối đa trong ngày (VND)", example = "500000000.00")
                BigDecimal maxDailyLimit,
        @Schema(
                        description = "Thời điểm cập nhật gần nhất (ISO-8601 UTC)",
                        example = "2026-10-09T01:00:00Z")
                Instant updatedAt,
        @Schema(description = "Phiên bản bản ghi (optimistic locking)", example = "0")
                Long version) {

    /** Maps a {@link CustomerType} domain entity to its API response representation. */
    public static CustomerTypeResponse from(CustomerType customerType) {
        return new CustomerTypeResponse(
                customerType.getId(),
                customerType.getCode(),
                customerType.getName(),
                customerType.getMaxTransactionLimit(),
                customerType.getMaxDailyLimit(),
                customerType.getUpdatedAt(),
                customerType.getVersion());
    }
}
