package com.example.corebanking.common.audit;

import com.example.corebanking.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative REST controller exposing immutable audit log queries at {@code GET
 * /api/v1/admin/audit-logs} restricted to {@code ROLE_ADMIN} (Story 6.3: NC-13, L2-01).
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
@Tag(
        name = "Admin Audit Logs",
        description = "Truy vết nhật ký kiểm toán các thao tác nhạy cảm dành cho Quản trị viên")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @Operation(
            summary = "Tra cứu nhật ký kiểm toán (Audit Log)",
            description =
                    "Lọc danh sách bản ghi kiểm toán bất biến theo người thực hiện (actor), hành"
                            + " động (action), loại thực thể (entityType) và khoảng thời gian"
                            + " (from/to).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách nhật ký kiểm toán phân trang",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Tham số lọc hoặc sắp xếp không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Chỉ Quản trị viên (ROLE_ADMIN) mới có quyền xem nhật ký kiểm toán",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<AuditLogResponse>> searchAuditLogs(
            @Parameter(description = "Lọc theo username người thực hiện", example = "admin")
                    @RequestParam(required = false)
                    String actor,
            @Parameter(description = "Lọc theo mã hành động", example = "ACCOUNT_STATUS_CHANGED")
                    @RequestParam(required = false)
                    String action,
            @Parameter(description = "Lọc theo loại thực thể", example = "ACCOUNT")
                    @RequestParam(required = false)
                    String entityType,
            @Parameter(description = "Từ ngày (YYYY-MM-DD)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @Parameter(description = "Đến ngày (YYYY-MM-DD)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @Parameter(description = "Từ ngày hoặc thời điểm ISO-8601")
                    @RequestParam(required = false)
                    String from,
            @Parameter(description = "Đến ngày hoặc thời điểm ISO-8601")
                    @RequestParam(required = false)
                    String to,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ResponseEntity.ok(
                auditLogService.searchAuditLogs(
                        actor, action, entityType, fromDate, toDate, from, to, pageable));
    }
}
