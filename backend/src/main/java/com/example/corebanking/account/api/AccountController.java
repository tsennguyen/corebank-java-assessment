package com.example.corebanking.account.api;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.api.dto.UpdateAccountLimitsRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.common.api.PageResponse;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** REST Controller exposing Account management and limit adjustment operations. */
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
    @Operation(
            summary = "Lấy chi tiết tài khoản",
            description = "Truy vấn thông tin tài khoản theo ID.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy chi tiết thành công",
                content = @Content(schema = @Schema(implementation = AccountResponse.class))),
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
                    @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC)
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

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Đóng tài khoản",
            description =
                    "Chuyển trạng thái tài khoản sang CLOSED nếu số dư bằng 0; từ chối 409 nếu còn"
                            + " số dư.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Đóng tài khoản thành công"),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Tài khoản vẫn còn số dư lớn hơn 0 (ACCOUNT_HAS_BALANCE)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Void> closeAccount(
            @Parameter(description = "ID nội bộ của tài khoản", example = "1") @PathVariable
                    Long id) {
        accountService.closeAccount(id);
        return ResponseEntity.noContent().build();
    }
}
