package com.example.corebanking.transaction.api;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransactionSearchCriteria;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.TransactionType;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** REST Controller exposing financial transaction operations: deposits, queries, and history. */
@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transaction", description = "Quản lý và thực hiện giao dịch tài chính ngân hàng")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/deposits")
    @Operation(
            summary = "Nạp tiền vào tài khoản",
            description =
                    "Ghi có (credit) tiền mặt vào tài khoản ngân hàng, cập nhật số dư nguyên tử với"
                            + " khóa bi quan và tạo bản ghi kiểm toán giao dịch.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Nạp tiền thành công",
                headers =
                        @Header(
                                name = "Location",
                                description = "URI tài nguyên giao dịch vừa tạo",
                                schema = @Schema(type = "string")),
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Dữ liệu nạp tiền không hợp lệ (số tiền âm, định dạng sai)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản đích",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description = "Tài khoản không ở trạng thái hoạt động (CLOSED hoặc LOCKED)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> deposit(@Valid @RequestBody DepositRequest request) {
        TransactionResponse response = transactionService.deposit(request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/transactions/{id}")
                        .buildAndExpand(response.id())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Lấy chi tiết giao dịch",
            description = "Truy vấn thông tin chi tiết một giao dịch tài chính theo ID.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy thông tin giao dịch thành công",
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy giao dịch",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> getTransactionById(
            @Parameter(description = "ID nội bộ của giao dịch", example = "1") @PathVariable
                    Long id) {
        return ResponseEntity.ok(transactionService.getTransactionById(id));
    }

    @GetMapping
    @Operation(
            summary = "Tìm kiếm và phân trang giao dịch",
            description =
                    "Tìm kiếm giao dịch theo tài khoản và loại giao dịch, hỗ trợ phân trang và sắp"
                            + " xếp có whitelist.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách giao dịch phân trang thành công",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Trường sắp xếp không hợp lệ hoặc kích thước trang vượt ngưỡng 100",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<TransactionResponse>> searchTransactions(
            @Parameter(description = "Lọc theo ID tài khoản (nguồn hoặc đích)", example = "1")
                    @RequestParam(required = false)
                    Long accountId,
            @Parameter(description = "Lọc theo loại giao dịch", example = "DEPOSIT")
                    @RequestParam(required = false)
                    TransactionType type,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        TransactionSearchCriteria criteria = new TransactionSearchCriteria(accountId, type);
        return ResponseEntity.ok(transactionService.searchTransactions(criteria, pageable));
    }
}
