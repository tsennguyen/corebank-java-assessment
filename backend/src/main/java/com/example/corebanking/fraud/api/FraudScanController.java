package com.example.corebanking.fraud.api;

import com.example.corebanking.fraud.api.dto.FraudScanRequest;
import com.example.corebanking.fraud.api.dto.FraudScanResponse;
import com.example.corebanking.fraud.application.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller triggering historical batch scans of outgoing transactions using PostgreSQL
 * window functions and idempotent alert persistence (L3-03, L3-04, FR-22, G-05).
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
@Tag(
        name = "Fraud & Alerts",
        description =
                "Quản lý cảnh báo giao dịch bất thường và quét lịch sử gian lận (L3-03, L3-04,"
                        + " FR-22, FR-23)")
public class FraudScanController {

    private final AlertService alertService;

    public FraudScanController(AlertService alertService) {
        this.alertService = alertService;
    }

    @PostMapping({"/api/v1/fraud/scans", "/api/v1/alerts/scan"})
    @Operation(
            summary = "Quét lịch sử giao dịch bất thường theo khoảng thời gian",
            description =
                    "Sử dụng SQL Window Function (COUNT(*) OVER (PARTITION BY source_account_id"
                        + " ORDER BY created_at RANGE ... PRECEDING)) và điều kiện ngưỡng giá trị"
                        + " kết hợp INSERT ... ON CONFLICT DO NOTHING để phát hiện và tạo cảnh báo"
                        + " còn thiếu một cách idempotent (L3-03, FR-22, G-05).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Hoàn tất quét lịch sử giao dịch bất thường",
                content = @Content(schema = @Schema(implementation = FraudScanResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Khoảng thời gian quét không hợp lệ hoặc vượt quá 366 ngày",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Chỉ quản trị viên (ADMIN) mới có quyền thực thi",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<FraudScanResponse> scanHistoricalTransactions(
            @Valid @RequestBody FraudScanRequest request) {
        return ResponseEntity.ok(alertService.scanHistoricalTransactions(request));
    }
}
