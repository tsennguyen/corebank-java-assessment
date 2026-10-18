package com.example.corebanking.fraud.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Response DTO summarizing the newly created alerts from a historical fraud scan (L3-03, FR-22).
 *
 * @param alertsCreated total number of newly inserted alerts across all rules
 * @param amountThresholdAlerts number of newly inserted {@code AMOUNT_THRESHOLD} alerts
 * @param velocityAlerts number of newly inserted {@code VELOCITY} alerts
 * @param scannedFrom resolved inclusive start instant (UTC)
 * @param scannedTo resolved inclusive end instant (UTC)
 */
@Schema(description = "Kết quả quét lịch sử giao dịch bất thường (L3-03, FR-22)")
public record FraudScanResponse(
        @Schema(description = "Tổng số cảnh báo mới được tạo", example = "3") int alertsCreated,
        @Schema(description = "Số cảnh báo vượt ngưỡng giá trị mới được tạo", example = "1")
                int amountThresholdAlerts,
        @Schema(
                        description = "Số cảnh báo tần suất giao dịch quá nhanh mới được tạo",
                        example = "2")
                int velocityAlerts,
        @Schema(
                        description = "Mốc bắt đầu quét đã chuẩn hóa (UTC)",
                        example = "2026-10-01T00:00:00Z")
                Instant scannedFrom,
        @Schema(
                        description = "Mốc kết thúc quét đã chuẩn hóa (UTC)",
                        example = "2026-10-18T23:59:59Z")
                Instant scannedTo) {}
