package com.example.corebanking.account.api;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.AccountStatusHistoryResponse;
import com.example.corebanking.account.api.dto.ChangeAccountStatusRequest;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.api.dto.UpdateAccountLimitsRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST Controller exposing Account management, limit adjustment, and status lifecycle operations.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Account", description = "Quản lý tài khoản thanh toán và hạn mức giao dịch")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @Operation(
            summary = "Mở tài khoản mới",
            description = "Mở tài khoản ngân hàng 12 chữ số theo chuẩn Luhn cho khách hàng.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Tài khoản được mở thành công",
                headers =
                        @Header(
                                name = "Location",
                                description = "URI tài nguyên tài khoản vừa tạo",
                                schema = @Schema(type = "string")),
                content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Hạn mức không hợp lệ hoặc dailyLimit < transactionLimit",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Khách hàng không tồn tại",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request) {
        AccountResponse response = accountService.createAccount(request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(response.id())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @ownership.canAccessAccount(authentication, #id)")
    @Operation(
            summary = "Lấy chi tiết tài khoản",
            description = "Truy vấn thông tin tài khoản theo ID.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy chi tiết thành công",
                content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Không có quyền truy cập tài khoản của khách hàng khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AccountResponse> getAccountById(
            @Parameter(description = "ID nội bộ của tài khoản", example = "1") @PathVariable
                    Long id) {
        return ResponseEntity.ok(accountService.getAccountById(id));
    }

    @GetMapping
    @Operation(
            summary = "Tìm kiếm và phân trang tài khoản",
            description =
                    "Lọc tài khoản theo khách hàng, trạng thái, số tài khoản; tính nhóm số dư"
                            + " balanceTier.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách tài khoản phân trang thành công",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Trường sắp xếp không hợp lệ hoặc kích thước trang vượt quá 100",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<AccountResponse>> searchAccounts(
            @Parameter(description = "ID khách hàng chủ tài khoản", example = "1")
                    @RequestParam(required = false)
                    Long customerId,
            @Parameter(description = "Trạng thái tài khoản", example = "ACTIVE")
                    @RequestParam(required = false)
                    AccountStatus status,
            @Parameter(description = "Số tài khoản tìm kiếm", example = "100000000016")
                    @RequestParam(required = false)
                    String accountNumber,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ResponseEntity.ok(
                accountService.searchAccounts(customerId, status, accountNumber, pageable));
    }

    @PatchMapping("/{id}")
    @Operation(
            summary = "Điều chỉnh hạn mức tài khoản",
            description =
                    "Cập nhật hạn mức giao dịch và hạn mức ngày với kiểm tra phiên bản version lạc"
                            + " quan.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cập nhật hạn mức thành công",
                content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Dữ liệu hạn mức không hợp lệ hoặc dailyLimit < transactionLimit",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Xung đột phiên bản dữ liệu (Concurrent Modification)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AccountResponse> updateLimits(
            @Parameter(description = "ID nội bộ của tài khoản", example = "1") @PathVariable
                    Long id,
            @Valid @RequestBody UpdateAccountLimitsRequest request) {
        return ResponseEntity.ok(accountService.updateLimits(id, request));
    }

    @PostMapping("/{id}/status")
    @Operation(
            summary = "Chuyển trạng thái tài khoản (ACTIVE, LOCKED, CLOSED)",
            description =
                    "Khóa, mở khóa hoặc đóng tài khoản theo máy trạng thái hữu hạn kèm lý do kiểm"
                            + " toán (L2-05, FR-3).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Chuyển trạng thái tài khoản thành công",
                content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Thiếu trạng thái đích hoặc lý do không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description =
                        "Chuyển trạng thái không hợp lệ (INVALID_STATUS_TRANSITION) hoặc tài khoản"
                                + " còn số dư khi đóng (ACCOUNT_HAS_BALANCE)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<AccountResponse> changeStatus(
            @Parameter(description = "ID nội bộ của tài khoản", example = "1") @PathVariable
                    Long id,
            @Valid @RequestBody ChangeAccountStatusRequest request) {
        return ResponseEntity.ok(accountService.changeStatus(id, request));
    }

    @GetMapping("/{id}/status-history")
    @PreAuthorize("hasRole('ADMIN') or @ownership.canAccessAccount(authentication, #id)")
    @Operation(
            summary = "Tra cứu lịch sử thay đổi trạng thái tài khoản",
            description =
                    "Lấy toàn bộ lịch sử chuyển đổi trạng thái của tài khoản sắp xếp giảm dần theo"
                            + " thời điểm thay đổi (L2-05, FR-3).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách lịch sử trạng thái tài khoản",
                content =
                        @Content(
                                array =
                                        @ArraySchema(
                                                schema =
                                                        @Schema(
                                                                implementation =
                                                                        AccountStatusHistoryResponse
                                                                                .class)))),
        @ApiResponse(
                responseCode = "403",
                description = "Không có quyền truy cập lịch sử của tài khoản khách hàng khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<List<AccountStatusHistoryResponse>> getStatusHistory(
            @Parameter(description = "ID nội bộ của tài khoản", example = "1") @PathVariable
                    Long id) {
        return ResponseEntity.ok(accountService.getStatusHistory(id));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Đóng tài khoản",
            description =
                    "Chuyển trạng thái tài khoản sang CLOSED nếu số dư bằng 0; từ chối 409 nếu còn"
                            + " số dư hoặc đã đóng.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Đóng tài khoản thành công"),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description =
                        "Tài khoản vẫn còn số dư lớn hơn 0 (ACCOUNT_HAS_BALANCE) hoặc đã ở trạng"
                                + " thái CLOSED (INVALID_STATUS_TRANSITION)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> closeAccount(
            @Parameter(description = "ID nội bộ của tài khoản", example = "1") @PathVariable
                    Long id) {
        accountService.closeAccount(id);
        return ResponseEntity.noContent().build();
    }
}
