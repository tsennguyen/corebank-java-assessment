package com.example.corebanking.transaction.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.OwnershipGuard;
import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountTransactionController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class AccountTransactionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private TransactionService transactionService;

    @MockBean(name = "ownership")
    private OwnershipGuard ownershipGuard;

    @Test
    @DisplayName("GET /api/v1/accounts/{id}/transactions returns 200 with paginated transactions")
    void getAccountTransactions_shouldReturn200() throws Exception {
        TransactionResponse tx =
                new TransactionResponse(
                        1L,
                        "TXN-20261012-ABC123DEF456",
                        TransactionType.DEPOSIT,
                        TransactionStatus.COMPLETED,
                        new BigDecimal("1500000.00"),
                        BigDecimal.ZERO,
                        null,
                        10L,
                        null,
                        new BigDecimal("1500000.00"),
                        new BigDecimal("1500000.00"),
                        "Hà Nội",
                        "Nạp tiền",
                        Instant.parse("2026-10-12T14:00:00Z"));

        PageResponse<TransactionResponse> pageResponse =
                new PageResponse<>(List.of(tx), 0, 20, 1L, 1);

        when(transactionService.getAccountTransactions(eq(10L), any(Pageable.class)))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/accounts/10/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].referenceNo").value("TXN-20261012-ABC123DEF456"))
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/accounts/{id}/transactions returns 404 when account does not exist")
    void getAccountTransactions_whenNotFound_shouldReturn404() throws Exception {
        when(transactionService.getAccountTransactions(eq(999L), any(Pageable.class)))
                .thenThrow(new ResourceNotFoundException("Account", 999L));

        mockMvc.perform(get("/api/v1/accounts/999/transactions"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @WithMockUser(username = "cust.an", roles = "CUSTOMER")
    @DisplayName(
            "GET /api/v1/accounts/{id}/transactions returns 200 when CUSTOMER accesses own account"
                    + " (AC 3)")
    void getAccountTransactions_whenCustomerAccessesOwnAccount_shouldReturn200() throws Exception {
        when(ownershipGuard.canAccessAccount(any(), eq(10L))).thenReturn(true);
        when(transactionService.getAccountTransactions(eq(10L), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/api/v1/accounts/10/transactions")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "cust.an", roles = "CUSTOMER")
    @DisplayName(
            "GET /api/v1/accounts/{id}/transactions returns 403 ACCESS_DENIED when CUSTOMER"
                    + " accesses another account (AC 3)")
    void getAccountTransactions_whenCustomerAccessesOtherAccount_shouldReturn403ProblemDetail()
            throws Exception {
        when(ownershipGuard.canAccessAccount(any(), eq(20L))).thenReturn(false);

        mockMvc.perform(get("/api/v1/accounts/20/transactions"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }
}
