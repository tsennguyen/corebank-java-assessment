package com.example.corebanking.auth.api;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.auth.infrastructure.OwnershipGuard;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.application.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller exposing self-service endpoints ({@code /api/v1/me}) for authenticated customers
 * to inspect their own profile and bank accounts without IDOR risk (L2-01, FR-14).
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(
        name = "Self-Service (Me)",
        description = "Cổng tự phục vụ xem hồ sơ và tài khoản cá nhân cho khách hàng đăng nhập")
public class MeController {

    private final CustomerService customerService;
    private final AccountService accountService;
    private final OwnershipGuard ownershipGuard;

    public MeController(
            CustomerService customerService,
            AccountService accountService,
            OwnershipGuard ownershipGuard) {
        this.customerService = customerService;
        this.accountService = accountService;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping
    @Operation(
            summary = "Xem hồ sơ cá nhân của khách hàng đang đăng nhập",
            description =
                    "Trích xuất customerId từ JWT đã ký HS256 và trả về hồ sơ khách hàng với số"
                            + " CCCD đã che (MaskingUtils).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy hồ sơ cá nhân thành công",
                content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "Chưa đăng nhập hoặc JWT không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Tài khoản đăng nhập không phải vai trò CUSTOMER",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Hồ sơ khách hàng không tồn tại hoặc đã bị xóa",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<CustomerResponse> getMyProfile(Authentication authentication) {
        Long customerId = ownershipGuard.requireCustomerId(authentication);
        return ResponseEntity.ok(customerService.getCustomerById(customerId));
    }

    @GetMapping("/accounts")
    @Operation(
            summary = "Lấy danh sách tài khoản của chính khách hàng đang đăng nhập",
            description =
                    "Trả về danh sách tài khoản thuộc sở hữu của customerId trong JWT, sắp xếp theo"
                            + " id giảm dần kèm phân nhóm số dư balanceTier.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy danh sách tài khoản cá nhân thành công",
                content =
                        @Content(
                                array =
                                        @ArraySchema(
                                                schema =
                                                        @Schema(
                                                                implementation =
                                                                        AccountResponse.class)))),
        @ApiResponse(
                responseCode = "401",
                description = "Chưa đăng nhập hoặc JWT không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Tài khoản đăng nhập không phải vai trò CUSTOMER",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<List<AccountResponse>> getMyAccounts(
            @Parameter(description = "Lọc tùy chọn theo trạng thái tài khoản", example = "ACTIVE")
                    @RequestParam(required = false)
                    AccountStatus status,
            Authentication authentication) {
        Long customerId = ownershipGuard.requireCustomerId(authentication);
        if (status != null) {
            return ResponseEntity.ok(accountService.getAccountsByCustomerId(customerId, status));
        }
        return ResponseEntity.ok(accountService.getAccountsByCustomerId(customerId));
    }
}
