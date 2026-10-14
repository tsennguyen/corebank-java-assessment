package com.example.corebanking.transaction.api;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.application.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST Controller exposing account transaction history queries within the transaction module. */
@RestController
@RequestMapping("/api/v1/accounts/{id}/transactions")
@Tag(name = "Transaction", description = "Quản lý và thực hiện giao dịch tài chính ngân hàng")
public class AccountTransactionController {

    private final TransactionService transactionService;

    public AccountTransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or @ownership.canAccessAccount(authentication, #id)")
    @Operation(
            summary = "Lấy lịch sử giao dịch của tài khoản",
            description =
                    "Truy vấn danh sách giao dịch nguồn và đích của tài khoản có phân trang và sắp"
                            + " xếp.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách giao dịch thành công",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Trường sắp xếp không hợp lệ hoặc kích thước trang vượt quá 100",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Không có quyền xem giao dịch của tài khoản khách hàng khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<TransactionResponse>> getAccountTransactions(
            @Parameter(description = "ID nội bộ của tài khoản", example = "1") @PathVariable
                    Long id,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        return ResponseEntity.ok(transactionService.getAccountTransactions(id, pageable));
    }
}
