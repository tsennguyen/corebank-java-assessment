package com.example.corebanking.account.api.dto;

import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.AccountStatusHistory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Response payload representing a single account status transition audit record (L2-05, FR-3). */
@Schema(description = "Bản ghi lịch sử thay đổi trạng thái tài khoản")
public record AccountStatusHistoryResponse(
        @Schema(description = "ID bản ghi lịch sử", example = "1") Long id,
        @Schema(description = "ID tài khoản", example = "10") Long accountId,
        @Schema(
                        description = "Trạng thái trước khi đổi (null nếu là lúc mở tài khoản)",
                        example = "ACTIVE",
                        nullable = true)
                AccountStatus fromStatus,
        @Schema(description = "Trạng thái sau khi đổi", example = "LOCKED") AccountStatus toStatus,
        @Schema(
                        description = "Lý do thay đổi trạng thái",
                        example = "Phong tỏa tạm thời để xác minh")
                String reason,
        @Schema(description = "Người thực hiện thay đổi", example = "admin") String changedBy,
        @Schema(description = "Thời điểm thay đổi (ISO-8601 UTC)", example = "2026-10-15T12:00:00Z")
                Instant changedAt) {

    public static AccountStatusHistoryResponse from(AccountStatusHistory entity) {
        return new AccountStatusHistoryResponse(
                entity.getId(),
                entity.getAccountId(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getReason(),
                entity.getChangedBy(),
                entity.getChangedAt());
    }
}
