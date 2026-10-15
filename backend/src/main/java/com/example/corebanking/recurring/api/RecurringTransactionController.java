package com.example.corebanking.recurring.api;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.recurring.api.dto.CreateRecurringTransactionRequest;
import com.example.corebanking.recurring.api.dto.RecurringTransactionResponse;
import com.example.corebanking.recurring.application.RecurringTransactionService;
import com.example.corebanking.recurring.domain.RecurringFrequency;
import com.example.corebanking.recurring.domain.RecurringStatus;
import com.example.corebanking.recurring.domain.RecurringTransactionType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST Controller exposing recurring transaction setup, search, pause, resume, and cancellation
 * operations (L2-06, FR-16).
 */
@RestController
@RequestMapping("/api/v1/recurring-transactions")
@Tag(
        name = "Recurring Transaction",
        description = "Thiết lập và quản lý vòng đời lệnh giao dịch định kỳ (L2-06)")
public class RecurringTransactionController {

    private final RecurringTransactionService recurringTransactionService;

    public RecurringTransactionController(RecurringTransactionService recurringTransactionService) {
        this.recurringTransactionService = recurringTransactionService;
    }

    @PostMapping
    @Operation(
            summary = "Thiết lập lệnh giao dịch định kỳ mới",
            description =
                    "Tạo lệnh nạp tiền (DEPOSIT), thanh toán (PAYMENT) hoặc chuyển khoản (TRANSFER)"
                            + " định kỳ theo tần suất DAILY, WEEKLY, MONTHLY (L2-06, FR-16).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Thiết lập lệnh định kỳ thành công",
                headers =
                        @Header(
                                name = "Location",
                                description = "URI tài nguyên lệnh định kỳ vừa tạo",
                                schema = @Schema(type = "string")),
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        RecurringTransactionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Dữ liệu không hợp lệ (amount <= 0, endDate < startDate, thiếu tài khoản"
                                + " theo loại, hoặc chuyển khoản cùng tài khoản)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Tài khoản nguồn hoặc đích không tồn tại",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Tài khoản nguồn hoặc đích không ở trạng thái ACTIVE",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<RecurringTransactionResponse> createRecurringTransaction(
            @Valid @RequestBody CreateRecurringTransactionRequest request) {
        RecurringTransactionResponse response =
                recurringTransactionService.createRecurringTransaction(request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.id())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Lấy chi tiết lệnh giao dịch định kỳ",
            description = "Truy vấn thông tin lệnh giao dịch định kỳ theo ID.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy chi tiết lệnh định kỳ thành công",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        RecurringTransactionResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy lệnh định kỳ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<RecurringTransactionResponse> getRecurringTransactionById(
            @Parameter(description = "ID của lệnh định kỳ", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(recurringTransactionService.getRecurringTransactionById(id));
    }

    @GetMapping
    @Operation(
            summary = "Tìm kiếm và phân trang lệnh giao dịch định kỳ",
            description =
                    "Lọc lệnh định kỳ theo trạng thái, tài khoản (nguồn hoặc đích), loại giao dịch"
                            + " và tần suất.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách lệnh định kỳ phân trang thành công",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Trường sắp xếp hoặc kích thước trang không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<RecurringTransactionResponse>> searchRecurringTransactions(
            @Parameter(description = "Trạng thái lệnh định kỳ", example = "ACTIVE")
                    @RequestParam(required = false)
                    RecurringStatus status,
            @Parameter(description = "ID tài khoản nguồn hoặc đích", example = "1")
                    @RequestParam(required = false)
                    Long accountId,
            @Parameter(description = "Loại giao dịch định kỳ", example = "PAYMENT")
                    @RequestParam(required = false)
                    RecurringTransactionType type,
            @Parameter(description = "Tần suất thực thi", example = "MONTHLY")
                    @RequestParam(required = false)
                    RecurringFrequency frequency,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ResponseEntity.ok(
                recurringTransactionService.searchRecurringTransactions(
                        status, accountId, type, frequency, pageable));
    }

    @PostMapping("/{id}/pause")
    @Operation(
            summary = "Tạm dừng lệnh giao dịch định kỳ",
            description = "Chuyển trạng thái lệnh định kỳ từ ACTIVE sang PAUSED (L2-06, FR-16).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tạm dừng lệnh định kỳ thành công",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        RecurringTransactionResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy lệnh định kỳ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Lệnh định kỳ không ở trạng thái ACTIVE (INVALID_STATUS_TRANSITION)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<RecurringTransactionResponse> pauseRecurringTransaction(
            @Parameter(description = "ID của lệnh định kỳ", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(recurringTransactionService.pauseRecurringTransaction(id));
    }

    @PostMapping("/{id}/resume")
    @Operation(
            summary = "Tiếp tục lệnh giao dịch định kỳ",
            description =
                    "Chuyển trạng thái lệnh định kỳ từ PAUSED sang ACTIVE, đặt lại failureCount = 0"
                            + " và kiểm tra tài khoản liên quan vẫn ACTIVE (L2-06, FR-16).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tiếp tục lệnh định kỳ thành công",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                implementation =
                                                        RecurringTransactionResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy lệnh định kỳ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description =
                        "Lệnh định kỳ không ở trạng thái PAUSED hoặc đã quá hạn endDate"
                                + " (INVALID_STATUS_TRANSITION)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Tài khoản liên quan không còn ở trạng thái ACTIVE",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<RecurringTransactionResponse> resumeRecurringTransaction(
            @Parameter(description = "ID của lệnh định kỳ", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(recurringTransactionService.resumeRecurringTransaction(id));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Hủy lệnh giao dịch định kỳ",
            description =
                    "Chuyển trạng thái lệnh định kỳ từ ACTIVE hoặc PAUSED sang CANCELLED (L2-06,"
                            + " FR-16).")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Hủy lệnh định kỳ thành công"),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy lệnh định kỳ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description =
                        "Lệnh định kỳ đã ở trạng thái kết thúc CANCELLED hoặc COMPLETED"
                                + " (INVALID_STATUS_TRANSITION)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> cancelRecurringTransaction(
            @Parameter(description = "ID của lệnh định kỳ", example = "1") @PathVariable Long id) {
        recurringTransactionService.cancelRecurringTransaction(id);
        return ResponseEntity.noContent().build();
    }
}
