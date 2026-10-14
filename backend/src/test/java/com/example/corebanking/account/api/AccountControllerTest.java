package com.example.corebanking.account.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.api.dto.UpdateAccountLimitsRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

@WebMvcTest(AccountController.class)
@Import({ClockConfig.class, GlobalExceptionHandler.class, RequestIdFilter.class})
class AccountControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private AccountService accountService;

    private AccountResponse createResponse(
            Long id,
            String accountNumber,
            Long customerId,
            BigDecimal balance,
            BalanceTier tier,
            BigDecimal txLimit,
            BigDecimal dailyLimit,
            AccountStatus status,
            Long version) {
        return new AccountResponse(
                id,
                accountNumber,
                customerId,
                "VND",
                balance,
                txLimit,
                dailyLimit,
                LocalDate.of(2026, 10, 9),
                status,
                tier,
                null,
                Instant.now(),
                null,
                version);
    }

    @Test
    @DisplayName("POST /api/v1/accounts returns 201 with Location header and account response")
    void createAccount_shouldReturn201WithLocationHeader() throws Exception {
        CreateAccountRequest request =
                new CreateAccountRequest(
                        1L, new BigDecimal("10000000.00"), new BigDecimal("50000000.00"));

        AccountResponse response =
                createResponse(
                        10L,
                        "100000000016",
                        1L,
                        new BigDecimal("0.00"),
                        BalanceTier.LOW,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        AccountStatus.ACTIVE,
                        0L);

        when(accountService.createAccount(any(CreateAccountRequest.class))).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/accounts/10")))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.accountNumber").value("100000000016"))
                .andExpect(jsonPath("$.balanceTier").value("LOW"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("VND"));
    }

    @Test
    @DisplayName("POST /api/v1/accounts returns 400 when validation fails on negative limit")
    void createAccount_shouldReturn400OnNegativeLimit() throws Exception {
        CreateAccountRequest request =
                new CreateAccountRequest(
                        1L, new BigDecimal("-1000.00"), new BigDecimal("50000000.00"));

        mockMvc.perform(
                        post("/api/v1/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/v1/accounts returns 404 when customer not found")
    void createAccount_shouldReturn404WhenCustomerNotFound() throws Exception {
        CreateAccountRequest request =
                new CreateAccountRequest(
                        999L, new BigDecimal("10000000.00"), new BigDecimal("50000000.00"));

        when(accountService.createAccount(any(CreateAccountRequest.class)))
                .thenThrow(new ResourceNotFoundException("Customer", 999L));

        mockMvc.perform(
                        post("/api/v1/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Customer not found with identifier: 999"));
    }

    @Test
    @DisplayName("GET /api/v1/accounts/{id} returns 200 with account details")
    void getAccountById_shouldReturn200() throws Exception {
        AccountResponse response =
                createResponse(
                        10L,
                        "100000000016",
                        1L,
                        new BigDecimal("50000000.00"),
                        BalanceTier.MEDIUM,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        AccountStatus.ACTIVE,
                        0L);

        when(accountService.getAccountById(10L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/accounts/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.balanceTier").value("MEDIUM"))
                .andExpect(jsonPath("$.balance").value(50000000.00));
    }

    @Test
    @DisplayName("GET /api/v1/accounts/{id} returns 404 when account missing")
    void getAccountById_shouldReturn404() throws Exception {
        when(accountService.getAccountById(999L))
                .thenThrow(new ResourceNotFoundException("Account", 999L));

        mockMvc.perform(get("/api/v1/accounts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/accounts returns 200 with paginated accounts")
    void searchAccounts_shouldReturn200() throws Exception {
        AccountResponse response =
                createResponse(
                        10L,
                        "100000000016",
                        1L,
                        new BigDecimal("0.00"),
                        BalanceTier.LOW,
                        new BigDecimal("10000000.00"),
                        new BigDecimal("50000000.00"),
                        AccountStatus.ACTIVE,
                        0L);

        PageResponse<AccountResponse> page = new PageResponse<>(List.of(response), 0, 20, 1L, 1);

        when(accountService.searchAccounts(
                        eq(1L), eq(AccountStatus.ACTIVE), eq("100000000016"), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(
                        get("/api/v1/accounts")
                                .param("customerId", "1")
                                .param("status", "ACTIVE")
                                .param("accountNumber", "100000000016")
                                .param("page", "0")
                                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].accountNumber").value("100000000016"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("PATCH /api/v1/accounts/{id} returns 200 when limits updated")
    void updateLimits_shouldReturn200() throws Exception {
        UpdateAccountLimitsRequest request =
                new UpdateAccountLimitsRequest(
                        new BigDecimal("20000000.00"), new BigDecimal("80000000.00"), 0L);

        AccountResponse response =
                createResponse(
                        10L,
                        "100000000016",
                        1L,
                        new BigDecimal("0.00"),
                        BalanceTier.LOW,
                        new BigDecimal("20000000.00"),
                        new BigDecimal("80000000.00"),
                        AccountStatus.ACTIVE,
                        1L);

        when(accountService.updateLimits(eq(10L), any(UpdateAccountLimitsRequest.class)))
                .thenReturn(response);

        mockMvc.perform(
                        patch("/api/v1/accounts/10")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionLimit").value(20000000.00))
                .andExpect(jsonPath("$.dailyLimit").value(80000000.00));
    }

    @Test
    @DisplayName("PATCH /api/v1/accounts/{id} returns 409 on version conflict")
    void updateLimits_shouldReturn409OnConflict() throws Exception {
        UpdateAccountLimitsRequest request =
                new UpdateAccountLimitsRequest(
                        new BigDecimal("20000000.00"), new BigDecimal("80000000.00"), 0L);

        when(accountService.updateLimits(eq(10L), any(UpdateAccountLimitsRequest.class)))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.CONCURRENT_MODIFICATION,
                                "Account modified by concurrent transaction"));

        mockMvc.perform(
                        patch("/api/v1/accounts/10")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("DELETE /api/v1/accounts/{id} returns 204 on successful closure")
    void closeAccount_shouldReturn204() throws Exception {
        doNothing().when(accountService).closeAccount(10L);

        mockMvc.perform(delete("/api/v1/accounts/10")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/accounts/{id} returns 409 when account has positive balance")
    void closeAccount_shouldReturn409WhenHasBalance() throws Exception {
        doThrow(
                        new BusinessRuleException(
                                ErrorCode.ACCOUNT_HAS_BALANCE,
                                "Account balance must be zero before closing"))
                .when(accountService)
                .closeAccount(10L);

        mockMvc.perform(delete("/api/v1/accounts/10"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_BALANCE"));
    }
}
