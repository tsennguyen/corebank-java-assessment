package com.example.corebanking.fraud.api;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.fraud.api.dto.AlertResponse;
import com.example.corebanking.fraud.api.dto.UpdateAlertStatusRequest;
import com.example.corebanking.fraud.application.AlertService;
import com.example.corebanking.fraud.domain.AlertRuleCode;
import com.example.corebanking.fraud.domain.AlertSeverity;
import com.example.corebanking.fraud.domain.AlertStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for querying and managing suspicious transaction alerts (L3-03, L3-04, FR-22,
 * FR-23).
 */
@RestController
@RequestMapping("/api/v1/alerts")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
        name = "Fraud & Alerts",
        description =
                "Quản lý cảnh báo giao dịch bất thường và quét lịch sử gian lận (L3-03, L3-04,"
                        + " FR-22, FR-23)")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    @Operation(
            summary = "Tìm kiếm và lọc danh sách cảnh báo bất thường",
            description =
                    "Lọc cảnh báo theo trạng thái (status), quy tắc (ruleCode), mức độ nghiêm trọng"
                            + " (severity), tài khoản (accountId) và khoảng thời gian (from/to hoặc"
                            + " fromDate/toDate) kèm phân trang và sắp xếp (L3-04, FR-23).")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Danh sách cảnh báo phân trang"),
        @ApiResponse(
                responseCode = "400",
                description = "Tham số lọc, phân trang hoặc trường sắp xếp không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Chỉ quản trị viên (ADMIN) mới có quyền truy cập",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<AlertResponse>> searchAlerts(
            @Parameter(
                            description =
                                    "Trạng thái cảnh báo (NEW, INVESTIGATING, RESOLVED,"
                                            + " FALSE_POSITIVE)")
                    @RequestParam(required = false)
                    AlertStatus status,
            @Parameter(description = "Mã quy tắc phát hiện (AMOUNT_THRESHOLD, VELOCITY)")
                    @RequestParam(required = false)
                    AlertRuleCode ruleCode,
            @Parameter(description = "Mức độ nghiêm trọng (MEDIUM, HIGH)")
                    @RequestParam(required = false)
                    AlertSeverity severity,
            @Parameter(description = "ID tài khoản nguồn") @RequestParam(required = false)
                    Long accountId,
            @Parameter(description = "Từ ngày (YYYY-MM-DD theo múi giờ Việt Nam)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @Parameter(description = "Đến ngày (YYYY-MM-DD theo múi giờ Việt Nam)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @Parameter(description = "Mốc bắt đầu (ISO-8601 Instant hoặc YYYY-MM-DD)")
                    @RequestParam(required = false)
                    String from,
            @Parameter(description = "Mốc kết thúc (ISO-8601 Instant hoặc YYYY-MM-DD)")
                    @RequestParam(required = false)
                    String to,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ResponseEntity.ok(
                alertService.searchAlerts(
                        status, ruleCode, severity, accountId, fromDate, toDate, from, to,
                        pageable));
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Xem chi tiết cảnh báo bất thường theo ID",
            description = "Lấy thông tin chi tiết của một cảnh báo giao dịch bất thường.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Thông tin cảnh báo",
                content = @Content(schema = @Schema(implementation = AlertResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy cảnh báo",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AlertResponse> getAlertById(
            @Parameter(description = "ID nội bộ của cảnh báo", example = "1") @PathVariable
                    Long id) {
        return ResponseEntity.ok(alertService.getAlertById(id));
    }

    @PostMapping("/{id}/status")
    @Operation(
            summary = "Chuyển trạng thái xử lý cảnh báo",
            description =
                    "Chuyển trạng thái cảnh báo theo máy trạng thái hữu hạn: NEW -> INVESTIGATING"
                            + " -> RESOLVED | FALSE_POSITIVE hoặc NEW -> FALSE_POSITIVE (L3-04,"
                            + " FR-23).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cập nhật trạng thái cảnh báo thành công",
                content = @Content(schema = @Schema(implementation = AlertResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Dữ liệu đầu vào không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy cảnh báo",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Chuyển đổi trạng thái không hợp lệ (INVALID_STATUS_TRANSITION)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AlertResponse> updateStatus(
            @Parameter(description = "ID nội bộ của cảnh báo", example = "1") @PathVariable Long id,
            @Valid @RequestBody UpdateAlertStatusRequest request) {
        return ResponseEntity.ok(alertService.updateStatus(id, request));
    }
}
