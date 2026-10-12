package com.example.corebanking.transaction.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransactionSearchCriteria;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransactionController.class)
@Import({ClockConfig.class, GlobalExceptionHandler.class, RequestIdFilter.class})
class TransactionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private TransactionService transactionService;

    private TransactionResponse createSampleResponse(Long id, Long accountId, BigDecimal amount) {
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

    @Test
    @DisplayName("POST /api/v1/transactions/deposits returns 201 with Location header when valid")
    void deposit_whenValid_shouldReturn201() throws Exception {
        DepositRequest request =
                new DepositRequest(1L, new BigDecimal("1500000.00"), "Hà Nội", "Nạp tiền ban đầu");
        TransactionResponse response = createSampleResponse(100L, 1L, new BigDecimal("1500000.00"));

        when(transactionService.deposit(any(DepositRequest.class))).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/transactions/100")))
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
    @DisplayName("POST /api/v1/transactions/deposits returns 400 when amount is negative or zero")
    void deposit_whenAmountNegativeOrZero_shouldReturn400() throws Exception {
        DepositRequest request =
                new DepositRequest(1L, new BigDecimal("-500.00"), "Hà Nội", "Nạp âm");

        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/transactions/deposits returns 400 when amount has scale > 2")
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
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/transactions/deposits returns 400 when accountId is null")
    void deposit_whenAccountIdNull_shouldReturn400() throws Exception {
        DepositRequest request =
                new DepositRequest(null, new BigDecimal("100000.00"), "Hà Nội", "Thiếu accountId");

        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/transactions/deposits returns 404 when account not found")
    void deposit_whenAccountNotFound_shouldReturn404() throws Exception {
        DepositRequest request =
                new DepositRequest(999L, new BigDecimal("100000.00"), "Hà Nội", "Không tồn tại");

        when(transactionService.deposit(any(DepositRequest.class)))
                .thenThrow(new ResourceNotFoundException("Account", 999L));

        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("POST /api/v1/transactions/deposits returns 422 when account is not active")
    void deposit_whenAccountNotActive_shouldReturn422() throws Exception {
        DepositRequest request =
                new DepositRequest(1L, new BigDecimal("100000.00"), "Hà Nội", "Đã đóng");

        when(transactionService.deposit(any(DepositRequest.class)))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.ACCOUNT_NOT_ACTIVE,
                                "Cannot credit account in status: CLOSED"));

        mockMvc.perform(
                        post("/api/v1/transactions/deposits")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
    }

    @Test
    @DisplayName("GET /api/v1/transactions/{id} returns 200 when exists")
    void getTransactionById_whenExists_shouldReturn200() throws Exception {
        TransactionResponse response = createSampleResponse(1L, 10L, new BigDecimal("500000.00"));
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
        TransactionResponse response = createSampleResponse(1L, 10L, new BigDecimal("500000.00"));
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
