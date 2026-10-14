package com.example.corebanking.transaction.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransactionController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class TransactionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TransactionService transactionService;

    private TransactionResponse createDepositResponse(Long id, Long accountId, BigDecimal amount) {
        return new TransactionResponse(
                id,
                "TXN-20261012-ABC123DEF456",
                TransactionType.DEPOSIT,
                TransactionStatus.COMPLETED,
                amount,
                BigDecimal.ZERO,
                null,
                accountId,
                null,
                amount,
                amount,
                "Hà Nội",
                "Nạp tiền ban đầu",
                Instant.parse("2026-10-12T14:30:00Z"));
    }

    private TransactionResponse createWithdrawalResponse(
            Long id, Long accountId, BigDecimal amount, BigDecimal fee) {
        return new TransactionResponse(
                id,
                "TXN-20261012-WTH000111222",
                TransactionType.WITHDRAWAL,
                TransactionStatus.COMPLETED,
                amount,
                fee,
                accountId,
                null,
                new BigDecimal("5000000.00").subtract(amount).subtract(fee),
                null,
                amount.add(fee).negate(),
                "ATM Q1",
                "Rút tiền mặt",
                Instant.parse("2026-10-12T14:30:00Z"));
    }

    private TransactionResponse createTransferResponse(
            Long id, Long sourceId, Long targetId, BigDecimal amount, BigDecimal fee) {
        return new TransactionResponse(
                id,
                "TXN-20261012-TRF000333444",
                TransactionType.TRANSFER,
                TransactionStatus.COMPLETED,
                amount,
                fee,
                sourceId,
                targetId,
                new BigDecimal("10000000.00").subtract(amount).subtract(fee),
                new BigDecimal("5000000.00").add(amount),
                fee.negate(),
                "Mobile Banking",
                "Chuyển tiền mua sắm",
                Instant.parse("2026-10-12T14:30:00Z"));
    }

    private TransactionResponse createPaymentResponse(
            Long id, Long sourceId, BigDecimal amount, BigDecimal fee) {
        return new TransactionResponse(
                id,
                "TXN-20261012-PAY000555666",
                TransactionType.PAYMENT,
                TransactionStatus.COMPLETED,
                amount,
                fee,
                sourceId,
                null,
                new BigDecimal("2000000.00").subtract(amount).subtract(fee),
                null,
                amount.negate(),
                "Online Gateway",
                "Thanh toán cước | EVN",
                Instant.parse("2026-10-12T14:30:00Z"));
    }

    // ==========================================
    // DEPOSIT ENDPOINT TESTS
    // ==========================================
    @Nested
    @DisplayName("POST /api/v1/transactions/deposits")
    class DepositEndpointTests {

        @Test
        @DisplayName("returns 201 with Location header when valid")
        void deposit_whenValid_shouldReturn201() throws Exception {
            DepositRequest request =
                    new DepositRequest(
                            1L, new BigDecimal("1500000.00"), "Hà Nội", "Nạp tiền ban đầu");
            TransactionResponse response =
                    createDepositResponse(100L, 1L, new BigDecimal("1500000.00"));

            when(transactionService.deposit(any(), any(DepositRequest.class)))
                    .thenReturn(IdempotencyResult.executed(response));

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header("Idempotency-Key", "test-key-deposit")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(
                            header().string("Location", containsString("/api/v1/transactions/100")))
                    .andExpect(jsonPath("$.id").value(100))
                    .andExpect(jsonPath("$.referenceNo").value("TXN-20261012-ABC123DEF456"))
                    .andExpect(jsonPath("$.type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.amount").value(1500000.00))
                    .andExpect(jsonPath("$.targetAccountId").value(1))
                    .andExpect(jsonPath("$.targetBalanceAfter").value(1500000.00))
                    .andExpect(jsonPath("$.systemBalanceDelta").value(1500000.00));
        }

        @Test
        @DisplayName("returns 400 when amount is negative or zero")
        void deposit_whenAmountNegativeOrZero_shouldReturn400() throws Exception {
            DepositRequest request =
                    new DepositRequest(1L, new BigDecimal("-500.00"), "Hà Nội", "Nạp âm");

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header("Idempotency-Key", "test-key-deposit")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("returns 400 when amount has scale > 2")
        void deposit_whenAmountScaleExceeds2_shouldReturn400() throws Exception {
            String invalidPayload =
                    """
                    {
                        "accountId": 1,
                        "amount": 100.123,
                        "location": "Hà Nội",
                        "description": "Scale 3"
                    }
                    """;

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header("Idempotency-Key", "test-key-deposit")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalidPayload))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("returns 400 when accountId is null")
        void deposit_whenAccountIdNull_shouldReturn400() throws Exception {
            DepositRequest request =
                    new DepositRequest(
                            null, new BigDecimal("100000.00"), "Hà Nội", "Thiếu accountId");

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header("Idempotency-Key", "test-key-deposit")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("returns 404 when account not found")
        void deposit_whenAccountNotFound_shouldReturn404() throws Exception {
            DepositRequest request =
                    new DepositRequest(
                            999L, new BigDecimal("100000.00"), "Hà Nội", "Không tồn tại");

            when(transactionService.deposit(any(), any(DepositRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Account", 999L));

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header("Idempotency-Key", "test-key-deposit")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("returns 422 when account is not active")
        void deposit_whenAccountNotActive_shouldReturn422() throws Exception {
            DepositRequest request =
                    new DepositRequest(1L, new BigDecimal("100000.00"), "Hà Nội", "Đã đóng");

            when(transactionService.deposit(any(), any(DepositRequest.class)))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.ACCOUNT_NOT_ACTIVE,
                                    "Cannot credit account in status: CLOSED"));

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header("Idempotency-Key", "test-key-deposit")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        }
    }

    // ==========================================
    // WITHDRAWAL ENDPOINT TESTS
    // ==========================================
    @Nested
    @DisplayName("POST /api/v1/transactions/withdrawals")
    class WithdrawalEndpointTests {

        @Test
        @DisplayName("returns 201 with Location header when valid")
        void withdraw_whenValid_shouldReturn201() throws Exception {
            WithdrawalRequest request =
                    new WithdrawalRequest(
                            1L, new BigDecimal("1000000.00"), "ATM Q1", "Rút tiền mặt");
            TransactionResponse response =
                    createWithdrawalResponse(
                            101L, 1L, new BigDecimal("1000000.00"), new BigDecimal("2000.00"));

            when(transactionService.withdraw(any(), any(WithdrawalRequest.class)))
                    .thenReturn(IdempotencyResult.executed(response));

            mockMvc.perform(
                            post("/api/v1/transactions/withdrawals")
                                    .header("Idempotency-Key", "test-key-withdraw")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(
                            header().string("Location", containsString("/api/v1/transactions/101")))
                    .andExpect(jsonPath("$.id").value(101))
                    .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.amount").value(1000000.00))
                    .andExpect(jsonPath("$.fee").value(2000.00))
                    .andExpect(jsonPath("$.sourceAccountId").value(1));
        }

        @Test
        @DisplayName("returns 400 when amount is zero or negative")
        void withdraw_whenAmountNegativeOrZero_shouldReturn400() throws Exception {
            WithdrawalRequest request =
                    new WithdrawalRequest(1L, BigDecimal.ZERO, "ATM", "Rút 0 đồng");

            mockMvc.perform(
                            post("/api/v1/transactions/withdrawals")
                                    .header("Idempotency-Key", "test-key-withdraw")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }

        @Test
        @DisplayName("returns 422 when balance is insufficient")
        void withdraw_whenInsufficientBalance_shouldReturn422() throws Exception {
            WithdrawalRequest request =
                    new WithdrawalRequest(1L, new BigDecimal("5000000.00"), "ATM", "Vượt số dư");

            when(transactionService.withdraw(any(), any(WithdrawalRequest.class)))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.INSUFFICIENT_BALANCE,
                                    "Insufficient account balance"));

            mockMvc.perform(
                            post("/api/v1/transactions/withdrawals")
                                    .header("Idempotency-Key", "test-key-withdraw")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        }

        @Test
        @DisplayName("returns 422 when per-transaction limit exceeded")
        void withdraw_whenLimitExceeded_shouldReturn422() throws Exception {
            WithdrawalRequest request =
                    new WithdrawalRequest(1L, new BigDecimal("60000000.00"), "ATM", "Vượt hạn mức");

            when(transactionService.withdraw(any(), any(WithdrawalRequest.class)))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.LIMIT_EXCEEDED,
                                    "Transaction amount exceeds per-transaction limit"));

            mockMvc.perform(
                            post("/api/v1/transactions/withdrawals")
                                    .header("Idempotency-Key", "test-key-withdraw")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"));
        }

        @Test
        @DisplayName("returns 422 when daily limit exceeded")
        void withdraw_whenDailyLimitExceeded_shouldReturn422() throws Exception {
            WithdrawalRequest request =
                    new WithdrawalRequest(
                            1L, new BigDecimal("50000000.00"), "ATM", "Vượt hạn mức ngày");

            when(transactionService.withdraw(any(), any(WithdrawalRequest.class)))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.DAILY_LIMIT_EXCEEDED,
                                    "Total daily spending exceeds daily limit"));

            mockMvc.perform(
                            post("/api/v1/transactions/withdrawals")
                                    .header("Idempotency-Key", "test-key-withdraw")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"));
        }
    }

    // ==========================================
    // TRANSFER ENDPOINT TESTS
    // ==========================================
    @Nested
    @DisplayName("POST /api/v1/transactions/transfers")
    class TransferEndpointTests {

        @Test
        @DisplayName("returns 201 with Location header when valid")
        void transfer_whenValid_shouldReturn201() throws Exception {
            TransferRequest request =
                    new TransferRequest(
                            1L, 2L, new BigDecimal("5000000.00"), "Mobile", "Chuyển tiền");
            TransactionResponse response =
                    createTransferResponse(
                            102L, 1L, 2L, new BigDecimal("5000000.00"), new BigDecimal("2000.00"));

            when(transactionService.transfer(any(), any(TransferRequest.class)))
                    .thenReturn(IdempotencyResult.executed(response));

            mockMvc.perform(
                            post("/api/v1/transactions/transfers")
                                    .header("Idempotency-Key", "test-key-transfer")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(
                            header().string("Location", containsString("/api/v1/transactions/102")))
                    .andExpect(jsonPath("$.id").value(102))
                    .andExpect(jsonPath("$.type").value("TRANSFER"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.sourceAccountId").value(1))
                    .andExpect(jsonPath("$.targetAccountId").value(2))
                    .andExpect(jsonPath("$.amount").value(5000000.00))
                    .andExpect(jsonPath("$.fee").value(2000.00));
        }

        @Test
        @DisplayName("returns 400 when source and target account IDs are identical")
        void transfer_whenSameAccount_shouldReturn400() throws Exception {
            TransferRequest request =
                    new TransferRequest(
                            1L, 1L, new BigDecimal("1000000.00"), "Mobile", "Tự chuyển");

            when(transactionService.transfer(any(), any(TransferRequest.class)))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.SAME_ACCOUNT_TRANSFER,
                                    "Source and target accounts cannot be identical"));

            mockMvc.perform(
                            post("/api/v1/transactions/transfers")
                                    .header("Idempotency-Key", "test-key-transfer")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
        }

        @Test
        @DisplayName("returns 400 when targetAccountId is missing")
        void transfer_whenMissingTargetId_shouldReturn400() throws Exception {
            TransferRequest request =
                    new TransferRequest(
                            1L, null, new BigDecimal("1000000.00"), "Mobile", "Thiếu target");

            mockMvc.perform(
                            post("/api/v1/transactions/transfers")
                                    .header("Idempotency-Key", "test-key-transfer")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    // ==========================================
    // PAYMENT ENDPOINT TESTS
    // ==========================================
    @Nested
    @DisplayName("POST /api/v1/transactions/payments")
    class PaymentEndpointTests {

        @Test
        @DisplayName("returns 201 with Location header when valid")
        void payment_whenValid_shouldReturn201() throws Exception {
            PaymentRequest request =
                    new PaymentRequest(
                            1L,
                            new BigDecimal("500000.00"),
                            "Online Gateway",
                            "Thanh toán cước",
                            "EVN");
            TransactionResponse response =
                    createPaymentResponse(103L, 1L, new BigDecimal("500000.00"), BigDecimal.ZERO);

            when(transactionService.payment(any(), any(PaymentRequest.class)))
                    .thenReturn(IdempotencyResult.executed(response));

            mockMvc.perform(
                            post("/api/v1/transactions/payments")
                                    .header("Idempotency-Key", "test-key-payment")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(
                            header().string("Location", containsString("/api/v1/transactions/103")))
                    .andExpect(jsonPath("$.id").value(103))
                    .andExpect(jsonPath("$.type").value("PAYMENT"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.sourceAccountId").value(1))
                    .andExpect(jsonPath("$.amount").value(500000.00));
        }

        @Test
        @DisplayName("returns 400 when sourceAccountId is null")
        void payment_whenMissingSourceId_shouldReturn400() throws Exception {
            PaymentRequest request =
                    new PaymentRequest(
                            null, new BigDecimal("500000.00"), "Online", "Thiếu source", null);

            mockMvc.perform(
                            post("/api/v1/transactions/payments")
                                    .header("Idempotency-Key", "test-key-payment")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    // ==========================================
    // IDEMPOTENCY KEY TESTS
    // ==========================================
    @Nested
    @DisplayName("Idempotency Key validation and replay tests")
    class IdempotencyKeyTests {

        @Test
        @DisplayName("returns 400 with IDEMPOTENCY_KEY_MISSING when header is missing")
        void deposit_whenIdempotencyKeyMissing_shouldReturn400() throws Exception {
            DepositRequest request =
                    new DepositRequest(
                            1L, new BigDecimal("100000.00"), "Hà Nội", "Nạp tiền không key");

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "Required header 'Idempotency-Key' is missing or"
                                                    + " blank"));
        }

        @Test
        @DisplayName("returns 400 with IDEMPOTENCY_KEY_MISSING when header is blank")
        void withdraw_whenIdempotencyKeyBlank_shouldReturn400() throws Exception {
            WithdrawalRequest request =
                    new WithdrawalRequest(
                            1L, new BigDecimal("100000.00"), "ATM", "Rút tiền key trắng");

            mockMvc.perform(
                            post("/api/v1/transactions/withdrawals")
                                    .header("Idempotency-Key", "   ")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "Required header 'Idempotency-Key' is missing or"
                                                    + " blank"));
        }

        @Test
        @DisplayName("returns 201 with Idempotent-Replayed: true when transaction was replayed")
        void transfer_whenReplayed_shouldReturn201WithReplayedHeader() throws Exception {
            TransferRequest request =
                    new TransferRequest(
                            1L, 2L, new BigDecimal("5000000.00"), "Mobile", "Chuyển tiền");
            TransactionResponse response =
                    createTransferResponse(
                            102L, 1L, 2L, new BigDecimal("5000000.00"), new BigDecimal("2000.00"));

            when(transactionService.transfer(any(), any(TransferRequest.class)))
                    .thenReturn(IdempotencyResult.replayed(response));

            mockMvc.perform(
                            post("/api/v1/transactions/transfers")
                                    .header("Idempotency-Key", "test-key-replay")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.id").value(102))
                    .andExpect(jsonPath("$.amount").value(5000000.00));
        }

        @Test
        @DisplayName("returns 422 IDEMPOTENCY_KEY_REUSED when key is reused with different payload")
        void payment_whenKeyReusedWithDifferentPayload_shouldReturn422() throws Exception {
            PaymentRequest request =
                    new PaymentRequest(
                            1L, new BigDecimal("500000.00"), "Online", "Thanh toán", "EVN");

            when(transactionService.payment(any(), any(PaymentRequest.class)))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                                    "Idempotency key 'test-reused' has already been used with a"
                                            + " different request payload"));

            mockMvc.perform(
                            post("/api/v1/transactions/payments")
                                    .header("Idempotency-Key", "test-reused")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            containsString(
                                                    "has already been used with a different"
                                                            + " request payload")));
        }

        @Test
        @DisplayName("returns 400 with VALIDATION_FAILED when Idempotency-Key exceeds 128 chars")
        void deposit_whenIdempotencyKeyExceeds128Chars_shouldReturn400() throws Exception {
            DepositRequest request =
                    new DepositRequest(
                            1L, new BigDecimal("100000.00"), "Hà Nội", "Key dài quá 128 ký tự");

            mockMvc.perform(
                            post("/api/v1/transactions/deposits")
                                    .header("Idempotency-Key", "k".repeat(129))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(
                            jsonPath("$.detail")
                                    .value(
                                            "Header 'Idempotency-Key' must not exceed 128"
                                                    + " characters"));
        }
    }

    // ==========================================
    // QUERY ENDPOINT TESTS
    // ==========================================
    @Nested
    @DisplayName("GET /api/v1/transactions")
    class QueryEndpointTests {

        @Test
        @DisplayName("GET /api/v1/transactions/{id} returns 200 when exists")
        void getTransactionById_whenExists_shouldReturn200() throws Exception {
            TransactionResponse response =
                    createDepositResponse(1L, 10L, new BigDecimal("500000.00"));
            when(transactionService.getTransactionById(1L)).thenReturn(response);

            mockMvc.perform(get("/api/v1/transactions/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.referenceNo").value("TXN-20261012-ABC123DEF456"))
                    .andExpect(jsonPath("$.amount").value(500000.00));
        }

        @Test
        @DisplayName("GET /api/v1/transactions/{id} returns 404 when not found")
        void getTransactionById_whenNotFound_shouldReturn404() throws Exception {
            when(transactionService.getTransactionById(999L))
                    .thenThrow(new ResourceNotFoundException("Transaction", 999L));

            mockMvc.perform(get("/api/v1/transactions/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }

        @Test
        @DisplayName("GET /api/v1/transactions returns 200 with paginated results")
        void searchTransactions_whenValid_shouldReturn200() throws Exception {
            TransactionResponse response =
                    createDepositResponse(1L, 10L, new BigDecimal("500000.00"));
            PageResponse<TransactionResponse> pageResponse =
                    new PageResponse<>(List.of(response), 0, 20, 1L, 1);

            when(transactionService.searchTransactions(
                            any(TransactionSearchCriteria.class), any(Pageable.class)))
                    .thenReturn(pageResponse);

            mockMvc.perform(get("/api/v1/transactions?accountId=10&type=DEPOSIT"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/transactions/{id} Tests")
    class PatchTransactionTests {

        @Test
        @DisplayName("PATCH /api/v1/transactions/{id} returns 200 with updated metadata")
        void updateTransaction_whenValid_shouldReturn200() throws Exception {
            TransactionResponse response =
                    new TransactionResponse(
                            1L,
                            "TXN-UPD001",
                            TransactionType.DEPOSIT,
                            TransactionStatus.COMPLETED,
                            new BigDecimal("100000.00"),
                            BigDecimal.ZERO,
                            null,
                            10L,
                            null,
                            new BigDecimal("100000.00"),
                            new BigDecimal("100000.00"),
                            "New Location",
                            "New Description",
                            Instant.parse("2026-10-12T14:30:00Z"));

            when(transactionService.updateTransactionMetadata(
                            any(Long.class), any(UpdateTransactionRequest.class)))
                    .thenReturn(response);

            UpdateTransactionRequest request =
                    new UpdateTransactionRequest("New Description", "New Location");

            mockMvc.perform(
                            patch("/api/v1/transactions/1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.description").value("New Description"))
                    .andExpect(jsonPath("$.location").value("New Location"));
        }

        @Test
        @DisplayName("PATCH /api/v1/transactions/{id} returns 400 when immutable field provided")
        void updateTransaction_withImmutableField_shouldReturn400() throws Exception {
            when(transactionService.updateTransactionMetadata(
                            any(Long.class), any(UpdateTransactionRequest.class)))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.IMMUTABLE_FIELD,
                                    "Financial attributes are immutable"));

            UpdateTransactionRequest request =
                    new UpdateTransactionRequest(
                            "Desc",
                            "Loc",
                            new BigDecimal("50000.00"),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null);

            mockMvc.perform(
                            patch("/api/v1/transactions/1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.code").value("IMMUTABLE_FIELD"));
        }

        @Test
        @DisplayName(
                "PATCH /api/v1/transactions/{id} captures extended immutable fields (status,"
                        + " currency, systemBalanceDelta) via @JsonAnySetter")
        void updateTransaction_withExtendedImmutableFields_shouldFlagHasImmutableFields()
                throws Exception {
            String rawPayload =
                    """
                    {
                        "description": "Desc",
                        "status": "REVERSED",
                        "currency": "USD"
                    }
                    """;
            UpdateTransactionRequest parsed =
                    objectMapper.readValue(rawPayload, UpdateTransactionRequest.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(parsed::validateImmutability)
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.IMMUTABLE_FIELD);
        }

        @Test
        @DisplayName("PATCH /api/v1/transactions/{id} returns 404 when transaction not found")
        void updateTransaction_whenNotFound_shouldReturn404() throws Exception {
            when(transactionService.updateTransactionMetadata(
                            any(Long.class), any(UpdateTransactionRequest.class)))
                    .thenThrow(new ResourceNotFoundException("Transaction", 999L));

            UpdateTransactionRequest request = new UpdateTransactionRequest("Desc", "Loc");

            mockMvc.perform(
                            patch("/api/v1/transactions/999")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/transactions/{id} Tests")
    class ReverseTransactionTests {

        @Test
        @DisplayName("DELETE /api/v1/transactions/{id} returns 200 with reversal response")
        void reverseTransaction_whenValid_shouldReturn200() throws Exception {
            TransactionResponse response =
                    new TransactionResponse(
                            2L,
                            "TXN-REV-001",
                            TransactionType.REVERSAL,
                            TransactionStatus.COMPLETED,
                            new BigDecimal("500000.00"),
                            BigDecimal.ZERO,
                            10L,
                            null,
                            new BigDecimal("1000000.00"),
                            null,
                            new BigDecimal("-500000.00"),
                            "HN Branch",
                            "Reversal of TXN-DEP001",
                            Instant.parse("2026-10-12T15:00:00Z"),
                            1L);

            when(transactionService.reverseTransaction(1L)).thenReturn(response);

            mockMvc.perform(delete("/api/v1/transactions/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(2))
                    .andExpect(jsonPath("$.type").value("REVERSAL"))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.reversalOfId").value(1));
        }

        @Test
        @DisplayName("DELETE /api/v1/transactions/{id} returns 409 when already reversed")
        void reverseTransaction_whenAlreadyReversed_shouldReturn409() throws Exception {
            when(transactionService.reverseTransaction(1L))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.ALREADY_REVERSED,
                                    "Transaction has already been reversed"));

            mockMvc.perform(delete("/api/v1/transactions/1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.code").value("ALREADY_REVERSED"));
        }

        @Test
        @DisplayName(
                "DELETE /api/v1/transactions/{id} returns 422 when recipient has insufficient"
                        + " balance")
        void reverseTransaction_whenInsufficientBalance_shouldReturn422() throws Exception {
            when(transactionService.reverseTransaction(1L))
                    .thenThrow(
                            new BusinessRuleException(
                                    ErrorCode.INSUFFICIENT_BALANCE,
                                    "Insufficient account balance"));

            mockMvc.perform(delete("/api/v1/transactions/1"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.status").value(422))
                    .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        }

        @Test
        @DisplayName("DELETE /api/v1/transactions/{id} returns 404 when transaction not found")
        void reverseTransaction_whenNotFound_shouldReturn404() throws Exception {
            when(transactionService.reverseTransaction(999L))
                    .thenThrow(new ResourceNotFoundException("Transaction", 999L));

            mockMvc.perform(delete("/api/v1/transactions/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }
}
