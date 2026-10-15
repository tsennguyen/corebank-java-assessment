package com.example.corebanking.transaction.api;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.idempotency.IdempotencyResult;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.PaymentRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransactionSearchCriteria;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.api.dto.UpdateTransactionRequest;
import com.example.corebanking.transaction.api.dto.WithdrawalRequest;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.TransactionStatus;
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
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
                        + " khóa bi quan và tạo bản ghi kiểm toán giao dịch. Hỗ trợ Idempotency-Key"
                        + " chống trùng lặp.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Nạp tiền thành công",
                headers = {
                    @Header(
                            name = "Location",
                            description = "URI tài nguyên giao dịch vừa tạo",
                            schema = @Schema(type = "string")),
                    @Header(
                            name = "Idempotent-Replayed",
                            description =
                                    "Trả về 'true' nếu kết quả được phát lại từ cache idempotent",
                            schema = @Schema(type = "boolean"))
                },
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Thiếu Idempotency-Key hoặc dữ liệu nạp tiền không hợp lệ (số tiền âm, định"
                                + " dạng sai)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản đích",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description =
                        "Tài khoản không ở trạng thái hoạt động hoặc Idempotency-Key bị tái sử dụng"
                                + " với payload khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> deposit(
            @Parameter(description = "Idempotency key chống trùng lặp giao dịch", required = true)
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {
        validateIdempotencyKey(idempotencyKey);
        IdempotencyResult<TransactionResponse> result =
                transactionService.deposit(idempotencyKey, request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/transactions/{id}")
                        .buildAndExpand(result.data().id())
                        .toUri();
        var builder = ResponseEntity.created(location);
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.data());
    }

    @PostMapping("/withdrawals")
    @Operation(
            summary = "Rút tiền từ tài khoản",
            description =
                    "Ghi nợ (debit) tiền mặt từ tài khoản kèm tính phí và kiểm tra hạn mức giao"
                        + " dịch / hạn mức ngày, cập nhật số dư nguyên tử. Hỗ trợ Idempotency-Key"
                        + " chống trùng lặp.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Rút tiền thành công",
                headers = {
                    @Header(
                            name = "Location",
                            description = "URI tài nguyên giao dịch vừa tạo",
                            schema = @Schema(type = "string")),
                    @Header(
                            name = "Idempotent-Replayed",
                            description =
                                    "Trả về 'true' nếu kết quả được phát lại từ cache idempotent",
                            schema = @Schema(type = "boolean"))
                },
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Thiếu Idempotency-Key hoặc dữ liệu rút tiền không hợp lệ (số tiền âm, định"
                                + " dạng sai)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản nguồn",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description =
                        "Không đủ số dư, vượt hạn mức giao dịch, vượt hạn mức ngày, tài khoản không"
                                + " hoạt động hoặc Idempotency-Key bị tái sử dụng với payload khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> withdraw(
            @Parameter(description = "Idempotency key chống trùng lặp giao dịch", required = true)
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody WithdrawalRequest request) {
        validateIdempotencyKey(idempotencyKey);
        IdempotencyResult<TransactionResponse> result =
                transactionService.withdraw(idempotencyKey, request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/transactions/{id}")
                        .buildAndExpand(result.data().id())
                        .toUri();
        var builder = ResponseEntity.created(location);
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.data());
    }

    @PostMapping("/transfers")
    @Operation(
            summary = "Chuyển khoản nội bộ giữa hai tài khoản",
            description =
                    "Chuyển tiền giữa hai tài khoản với cơ chế khóa bi quan sắp xếp ID tăng dần"
                            + " chống deadlock, kiểm tra hạn mức và tự động tính phí. Hỗ trợ"
                            + " Idempotency-Key chống trùng lặp.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Chuyển tiền thành công",
                headers = {
                    @Header(
                            name = "Location",
                            description = "URI tài nguyên giao dịch vừa tạo",
                            schema = @Schema(type = "string")),
                    @Header(
                            name = "Idempotent-Replayed",
                            description =
                                    "Trả về 'true' nếu kết quả được phát lại từ cache idempotent",
                            schema = @Schema(type = "boolean"))
                },
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Thiếu Idempotency-Key, dữ liệu không hợp lệ hoặc tài khoản nguồn và đích"
                                + " trùng nhau",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản nguồn hoặc đích",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description =
                        "Không đủ số dư, vượt hạn mức giao dịch, vượt hạn mức ngày, tài khoản không"
                                + " hoạt động hoặc Idempotency-Key bị tái sử dụng với payload khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> transfer(
            @Parameter(description = "Idempotency key chống trùng lặp giao dịch", required = true)
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        validateIdempotencyKey(idempotencyKey);
        IdempotencyResult<TransactionResponse> result =
                transactionService.transfer(idempotencyKey, request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/transactions/{id}")
                        .buildAndExpand(result.data().id())
                        .toUri();
        var builder = ResponseEntity.created(location);
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.data());
    }

    @PostMapping("/payments")
    @Operation(
            summary = "Thanh toán dịch vụ / hóa đơn",
            description =
                    "Ghi nợ tài khoản để thanh toán dịch vụ hoặc hóa đơn kèm kiểm tra hạn mức và"
                            + " tính phí. Hỗ trợ Idempotency-Key chống trùng lặp.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Thanh toán thành công",
                headers = {
                    @Header(
                            name = "Location",
                            description = "URI tài nguyên giao dịch vừa tạo",
                            schema = @Schema(type = "string")),
                    @Header(
                            name = "Idempotent-Replayed",
                            description =
                                    "Trả về 'true' nếu kết quả được phát lại từ cache idempotent",
                            schema = @Schema(type = "boolean"))
                },
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Thiếu Idempotency-Key hoặc dữ liệu thanh toán không hợp lệ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy tài khoản trích nợ",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description =
                        "Không đủ số dư, vượt hạn mức, tài khoản không hoạt động hoặc"
                                + " Idempotency-Key bị tái sử dụng với payload khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> payment(
            @Parameter(description = "Idempotency key chống trùng lặp giao dịch", required = true)
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        validateIdempotencyKey(idempotencyKey);
        IdempotencyResult<TransactionResponse> result =
                transactionService.payment(idempotencyKey, request);
        URI location =
                ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/v1/transactions/{id}")
                        .buildAndExpand(result.data().id())
                        .toUri();
        var builder = ResponseEntity.created(location);
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.data());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessRuleException(
                    ErrorCode.IDEMPOTENCY_KEY_MISSING,
                    "Required header 'Idempotency-Key' is missing or blank");
        }
        if (idempotencyKey.trim().length() > 128) {
            throw new BusinessRuleException(
                    ErrorCode.VALIDATION_FAILED,
                    "Header 'Idempotency-Key' must not exceed 128 characters");
        }
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or @ownership.canAccessTransaction(authentication, #id)")
    @Operation(
            summary = "Lấy chi tiết giao dịch",
            description = "Truy vấn thông tin chi tiết một giao dịch tài chính theo ID.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Lấy thông tin giao dịch thành công",
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Không có quyền truy cập giao dịch không thuộc tài khoản sở hữu",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
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

    @PatchMapping("/{id}")
    @Operation(
            summary = "Cập nhật thông tin giao dịch",
            description =
                    "Cập nhật các trường thông tin mô tả và địa điểm của giao dịch. Các trường tài"
                            + " chính (số tiền, phí, tài khoản) là bất biến.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Cập nhật giao dịch thành công",
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Dữ liệu không hợp lệ hoặc cố tình sửa đổi trường bất biến"
                                + " (IMMUTABLE_FIELD)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy giao dịch",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Không thể sửa giao dịch đã bị đảo",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> updateTransaction(
            @Parameter(description = "ID nội bộ của giao dịch", example = "1") @PathVariable
                    Long id,
            @Valid @RequestBody UpdateTransactionRequest request) {
        return ResponseEntity.ok(transactionService.updateTransactionMetadata(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(
            summary = "Đảo giao dịch (Reversal)",
            description =
                    "Thực hiện đảo giao dịch tài chính để hoàn tiền/phí và cập nhật số dư, bảo đảm"
                            + " toàn vẹn vết kiểm toán. Giao dịch gốc chuyển sang REVERSED.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Đảo giao dịch thành công, trả về bản ghi REVERSAL",
                content = @Content(schema = @Schema(implementation = TransactionResponse.class))),
        @ApiResponse(
                responseCode = "404",
                description = "Không tìm thấy giao dịch",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "409",
                description = "Giao dịch đã bị đảo trước đó (ALREADY_REVERSED) hoặc không thể đảo",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "422",
                description =
                        "Tài khoản không ở trạng thái hoạt động (ACCOUNT_NOT_ACTIVE) hoặc không đủ"
                                + " số dư để hoàn lại tiền (INSUFFICIENT_BALANCE)",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<TransactionResponse> reverseTransaction(
            @Parameter(description = "ID nội bộ của giao dịch cần đảo", example = "1") @PathVariable
                    Long id) {
        return ResponseEntity.ok(transactionService.reverseTransaction(id));
    }

    @GetMapping
    @PreAuthorize(
            "hasRole('ADMIN') or #accountId == null or"
                    + " @ownership.canAccessAccount(authentication, #accountId)")
    @Operation(
            summary = "Tìm kiếm và phân trang giao dịch nâng cao",
            description =
                    "Tìm kiếm giao dịch kết hợp nhiều bộ lọc (tài khoản, loại, trạng thái, khoảng"
                        + " số tiền, khoảng ngày theo múi giờ Asia/Ho_Chi_Minh), hỗ trợ phân trang"
                        + " và sắp xếp có whitelist (createdAt, amount, fee, id).")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Danh sách giao dịch phân trang thành công",
                content = @Content(schema = @Schema(implementation = PageResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Trường sắp xếp không hợp lệ, khoảng số tiền/ngày không hợp lệ hoặc kích"
                                + " thước trang vượt ngưỡng 100",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
        @ApiResponse(
                responseCode = "403",
                description = "Không có quyền lọc giao dịch theo tài khoản của khách hàng khác",
                content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<PageResponse<TransactionResponse>> searchTransactions(
            @Parameter(description = "Lọc theo ID tài khoản (nguồn hoặc đích)", example = "1")
                    @RequestParam(required = false)
                    Long accountId,
            @Parameter(description = "Lọc theo loại giao dịch", example = "TRANSFER")
                    @RequestParam(required = false)
                    TransactionType type,
            @Parameter(description = "Lọc theo trạng thái giao dịch", example = "COMPLETED")
                    @RequestParam(required = false)
                    TransactionStatus status,
            @Parameter(description = "Số tiền giao dịch tối thiểu (VND)", example = "1000000")
                    @RequestParam(required = false)
                    BigDecimal minAmount,
            @Parameter(description = "Số tiền giao dịch tối đa (VND)", example = "5000000")
                    @RequestParam(required = false)
                    BigDecimal maxAmount,
            @Parameter(
                            description = "Từ ngày (YYYY-MM-DD, theo múi giờ Asia/Ho_Chi_Minh)",
                            example = "2026-09-01")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate fromDate,
            @Parameter(
                            description =
                                    "Đến hết ngày (YYYY-MM-DD, bao gồm trọn ngày theo múi giờ"
                                            + " Asia/Ho_Chi_Minh)",
                            example = "2026-09-30")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate toDate,
            @ParameterObject
                    @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                    Pageable pageable) {
        TransactionSearchCriteria criteria =
                new TransactionSearchCriteria(
                        accountId, type, status, minAmount, maxAmount, fromDate, toDate);
        return ResponseEntity.ok(transactionService.searchTransactions(criteria, pageable));
    }
}
