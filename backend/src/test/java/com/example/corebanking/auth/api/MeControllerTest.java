package com.example.corebanking.auth.api;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.infrastructure.JwtTokenService;
import com.example.corebanking.auth.infrastructure.OwnershipGuard;
import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.application.CustomerService;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeController.class)
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class,
    JwtTokenService.class,
    OwnershipGuard.class
})
class MeControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenService jwtTokenService;

    @MockitoBean private CustomerService customerService;
    @MockitoBean private AccountService accountService;
    @MockitoBean private AccountRepository accountRepository;
    @MockitoBean private TransactionRepository transactionRepository;

    private String issueCustomerToken(Long userId, String username, Long customerId) {
        AppUser user = AppUser.createCustomer(username, "$2a$10$hash", customerId);
        ReflectionTestUtils.setField(user, "id", userId);
        return jwtTokenService.generateAccessToken(user);
    }

    private String issueAdminToken() {
        AppUser admin = AppUser.createAdmin("admin", "$2a$10$hash");
        ReflectionTestUtils.setField(admin, "id", 1L);
        return jwtTokenService.generateAccessToken(admin);
    }

    @Test
    @DisplayName(
            "GET /api/v1/me returns 200 OK with masked nationalId for authenticated CUSTOMER"
                    + " (AC 1)")
    void getMe_whenAuthenticatedAsCustomer_shouldReturn200WithMaskedNationalId() throws Exception {
        String token = issueCustomerToken(2L, "cust.an", 10L);
        CustomerResponse profile =
                new CustomerResponse(
                        10L,
                        "CUS0000010",
                        "Nguyễn Văn An",
                        "*********101",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Đường Mẫu, Quận 1",
                        "TP. Hồ Chí Minh",
                        LocalDate.of(1992, 5, 15),
                        Instant.parse("2026-10-14T08:00:00Z"),
                        Instant.parse("2026-10-14T08:00:00Z"),
                        0L);

        when(customerService.getCustomerById(10L)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.customerCode").value("CUS0000010"))
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.nationalId").value("*********101"));
    }

    @Test
    @DisplayName(
            "GET /api/v1/me and /api/v1/me/accounts return 403 ACCESS_DENIED when called by ADMIN"
                    + " without customerId (AC 1)")
    void getMe_whenAuthenticatedAsAdminWithoutCustomerId_shouldReturn403AccessDenied()
            throws Exception {
        String adminToken = issueAdminToken();

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(
                        get("/api/v1/me/accounts")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(customerService, accountService);
    }

    @Test
    @DisplayName(
            "GET /api/v1/me returns 404 RESOURCE_NOT_FOUND when customer profile is soft-deleted"
                    + " (AC 1)")
    void getMe_whenCustomerSoftDeleted_shouldReturn404() throws Exception {
        String token = issueCustomerToken(2L, "cust.deleted", 99L);
        when(customerService.getCustomerById(99L))
                .thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName(
            "GET /api/v1/me/accounts returns only accounts owned by the authenticated CUSTOMER"
                    + " (AC 2)")
    void getMyAccounts_whenAuthenticatedAsCustomer_shouldReturnOnlyOwnAccounts() throws Exception {
        String token = issueCustomerToken(2L, "cust.an", 10L);
        AccountResponse acc1 =
                new AccountResponse(
                        102L,
                        "100800000026",
                        10L,
                        "VND",
                        new BigDecimal("150000000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("200000000.00"),
                        LocalDate.of(2026, 10, 14),
                        AccountStatus.ACTIVE,
                        BalanceTier.HIGH,
                        null,
                        Instant.parse("2026-10-14T08:00:00Z"),
                        Instant.parse("2026-10-14T08:00:00Z"),
                        0L);
        AccountResponse acc2 =
                new AccountResponse(
                        101L,
                        "100800000018",
                        10L,
                        "VND",
                        new BigDecimal("5000000.00"),
                        new BigDecimal("20000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.of(2026, 10, 14),
                        AccountStatus.ACTIVE,
                        BalanceTier.LOW,
                        null,
                        Instant.parse("2026-10-14T08:00:00Z"),
                        Instant.parse("2026-10-14T08:00:00Z"),
                        0L);

        when(accountService.getAccountsByCustomerId(10L)).thenReturn(List.of(acc1, acc2));

        mockMvc.perform(
                        get("/api/v1/me/accounts")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(102))
                .andExpect(jsonPath("$[0].customerId").value(10))
                .andExpect(jsonPath("$[0].balanceTier").value("HIGH"))
                .andExpect(jsonPath("$[1].id").value(101))
                .andExpect(jsonPath("$[1].balanceTier").value("LOW"));
    }

    @Test
    @DisplayName("GET /api/v1/me/accounts supports optional status filter for CUSTOMER (AC 2)")
    void getMyAccounts_withStatusFilter_shouldFilterByStatus() throws Exception {
        String token = issueCustomerToken(2L, "cust.an", 10L);
        when(accountService.getAccountsByCustomerId(10L, AccountStatus.ACTIVE))
                .thenReturn(List.of());

        mockMvc.perform(
                        get("/api/v1/me/accounts")
                                .param("status", "ACTIVE")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
