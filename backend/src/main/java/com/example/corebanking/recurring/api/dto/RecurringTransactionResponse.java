package com.example.corebanking.recurring.api.dto;

import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransaction;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response representation of a scheduled recurring transaction instruction (L2-06, L2-07, FR-16).
 */
@Schema(description = "Thông tin chi tiết lệnh giao dịch định kỳ")
public record RecurringTransactionResponse(
        @Schema(description = "ID nội bộ của lệnh định kỳ", example = "1") Long id,
        @Schema(description = "Loại giao dịch định kỳ", example = "PAYMENT")
                RecurringTransactionType type,
        @Schema(description = "ID tài khoản nguồn", example = "1") Long sourceAccountId,
        @Schema(description = "ID tài khoản đích", example = "2") Long targetAccountId,
        @Schema(description = "Số tiền mỗi kỳ (VND)", example = "5000000.00") BigDecimal amount,
        @Schema(description = "Địa điểm/kênh giao dịch", example = "Hà Nội") String location,
        @Schema(description = "Diễn giải giao dịch định kỳ", example = "Thanh toán định kỳ")
                String description,
        @Schema(description = "Tần suất thực thi", example = "MONTHLY")
                RecurringFrequency frequency,
        @Schema(description = "Ngày bắt đầu", example = "2026-10-15") LocalDate startDate,
        @Schema(description = "Ngày kết thúc", example = "2027-10-15") LocalDate endDate,
        @Schema(description = "Thời điểm thực thi kỳ kế tiếp (UTC ISO-8601)") Instant nextRunAt,
        @Schema(description = "Thời điểm thực thi gần nhất (UTC ISO-8601)") Instant lastRunAt,
        @Schema(description = "Trạng thái lệnh định kỳ", example = "ACTIVE") RecurringStatus status,
        @Schema(description = "Số lần thất bại liên tiếp", example = "0") int failureCount,
        @Schema(description = "Lỗi gần nhất nếu có") String lastError,
        @Schema(description = "Người tạo lệnh", example = "admin") String createdBy,
        @Schema(description = "Thời điểm tạo bản ghi") Instant createdAt,
        @Schema(description = "Thời điểm cập nhật bản ghi") Instant updatedAt,
        @Schema(description = "Phiên bản khóa lạc quan", example = "0") Long version) {

    /** Maps a {@link RecurringTransaction} domain entity to its API response DTO. */
    public static RecurringTransactionResponse from(RecurringTransaction entity) {
        return new RecurringTransactionResponse(
                entity.getId(),
                entity.getType(),
                entity.getSourceAccountId(),
                entity.getTargetAccountId(),
                entity.getAmount(),
                entity.getLocation(),
                entity.getDescription(),
                entity.getFrequency(),
                entity.getStartDate(),
                entity.getEndDate(),
                entity.getNextRunAt(),
                entity.getLastRunAt(),
                entity.getStatus(),
                entity.getFailureCount(),
                entity.getLastError(),
                entity.getCreatedBy(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion());
    }
}
