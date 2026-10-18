package com.example.corebanking.fraud.api.dto;

import com.example.corebanking.fraud.domain.Alert;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AlertStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Response DTO representing a suspicious transaction alert (L3-03, L3-04, FR-22, FR-23).
 *
 * @param id internal alert identifier
 * @param transactionId flagged transaction identifier
 * @param accountId source account identifier
 * @param ruleCode anomaly detection rule code
 * @param severity alert severity level
 * @param reason human-readable explanation of the anomaly
 * @param status current alert investigation status
 * @param resolutionNote optional investigation or resolution note
 * @param handledBy username of administrator who last updated the alert
 * @param createdAt timestamp when the alert was raised (UTC)
 * @param updatedAt timestamp when the alert was last updated (UTC)
 * @param version optimistic locking version
 */
@Schema(description = "Thông tin cảnh báo giao dịch bất thường (L3-03, L3-04, FR-22, FR-23)")
public record AlertResponse(
        @Schema(description = "ID nội bộ của cảnh báo", example = "1") Long id,
        @Schema(description = "ID giao dịch phát sinh cảnh báo", example = "101")
                Long transactionId,
        @Schema(description = "ID tài khoản nguồn liên quan", example = "1") Long accountId,
        @Schema(description = "Mã quy tắc phát hiện bất thường", example = "VELOCITY")
                AlertRuleCode ruleCode,
        @Schema(description = "Mức độ nghiêm trọng của cảnh báo", example = "MEDIUM")
                AlertSeverity severity,
        @Schema(
                        description = "Lý do chi tiết của cảnh báo",
                        example =
                                "Account 1 executed 6 outgoing transactions within 60 seconds"
                                        + " (threshold > 5)")
                String reason,
        @Schema(description = "Trạng thái xử lý hiện tại", example = "NEW") AlertStatus status,
        @Schema(
                        description = "Ghi chú điều tra hoặc kết luận xử lý",
                        example = "Verified with account holder")
                String resolutionNote,
        @Schema(description = "Tên quản trị viên xử lý cảnh báo", example = "admin")
                String handledBy,
        @Schema(description = "Thời điểm tạo cảnh báo (UTC)", example = "2026-10-18T06:00:00Z")
                Instant createdAt,
        @Schema(description = "Thời điểm cập nhật gần nhất (UTC)", example = "2026-10-18T06:05:00Z")
                Instant updatedAt,
        @Schema(description = "Phiên bản kiểm soát đồng thời lạc quan", example = "0")
                Long version) {

    public static AlertResponse from(Alert alert) {
        return new AlertResponse(
                alert.getId(),
                alert.getTransactionId(),
                alert.getAccountId(),
                alert.getRuleCode(),
                alert.getSeverity(),
                alert.getReason(),
                alert.getStatus(),
                alert.getResolutionNote(),
                alert.getHandledBy(),
                alert.getCreatedAt(),
                alert.getUpdatedAt(),
                alert.getVersion());
    }
}
