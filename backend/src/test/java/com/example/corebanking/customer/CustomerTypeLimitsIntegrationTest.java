package com.example.corebanking.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.common.config.CacheConfig;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerRequest;
import com.example.corebanking.customer.api.dto.UpdateCustomerTypeRequest;
import com.example.corebanking.customer.application.CustomerService;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.application.TransactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
@DisplayName("Story 4.1 Customer Type Limits Integration Tests (L3-05, L3-06)")
class CustomerTypeLimitsIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger NATIONAL_ID_SEQ = new AtomicInteger(410000);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private CacheManager cacheManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Statistics hibernateStats;

    @BeforeEach
    void initHibernateStatistics() {
        hibernateStats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        hibernateStats.setStatisticsEnabled(true);
        hibernateStats.clear();
    }

    private String nextSyntheticNationalId() {
        return String.format("000999%06d", NATIONAL_ID_SEQ.incrementAndGet());
    }

    @Test
    @DisplayName(
            "AC1 & AC2: Customer creation defaults to INDIVIDUAL, accepts BUSINESS, updates"
                    + " customerTypeCode, and filters by customerTypeCode with bounded SQL count")
    void customerLifecycle_shouldDefaultToIndividualAndSupportBusinessClassification()
            throws Exception {
        // 1. Omit customerTypeCode -> defaults to INDIVIDUAL
        CreateCustomerRequest individualReq =
                new CreateCustomerRequest(
                        "Cá Nhân Mặc Định",
                        nextSyntheticNationalId(),
                        "individual.41@example.com",
                        "0904100001",
                        "12 Phố Huế",
                        "Hà Nội",
                        LocalDate.of(1992, 5, 10));

        CustomerResponse createdIndividual = customerService.createCustomer(individualReq);
        assertThat(createdIndividual.customerTypeCode()).isEqualTo("INDIVIDUAL");

        // 2. Specify customerTypeCode = BUSINESS
        CreateCustomerRequest businessReq =
                new CreateCustomerRequest(
                        "Công Ty TNHH Mẫu 41",
                        nextSyntheticNationalId(),
                        "business.41@example.com",
                        "0904100002",
                        "88 Nguyễn Huệ",
                        "Hồ Chí Minh",
                        LocalDate.of(1985, 1, 1),
                        "BUSINESS");

        CustomerResponse createdBusiness = customerService.createCustomer(businessReq);
        assertThat(createdBusiness.customerTypeCode()).isEqualTo("BUSINESS");

        // 3. Filter GET /api/v1/customers by customerTypeCode=BUSINESS (assert <= 2 SQL queries per
        // Rule 16)
        hibernateStats.clear();
        mockMvc.perform(
                        get("/api/v1/customers")
                                .param("name", "Công Ty TNHH Mẫu 41")
                                .param("customerTypeCode", "BUSINESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customerTypeCode").value("BUSINESS"));
        assertThat(hibernateStats.getPrepareStatementCount())
                .as(
                        "Filtered customer search by customerTypeCode must execute at most 2 SQL"
                                + " statements (1 SELECT + optional 1 COUNT)")
                .isLessThanOrEqualTo(2L);

        // 4. Update INDIVIDUAL customer to BUSINESS
        UpdateCustomerRequest promoteReq =
                new UpdateCustomerRequest(
                        createdIndividual.fullName(),
                        createdIndividual.email(),
                        createdIndividual.phone(),
                        createdIndividual.address(),
                        createdIndividual.location(),
                        createdIndividual.dateOfBirth(),
                        createdIndividual.version(),
                        "BUSINESS");

        CustomerResponse promoted =
                customerService.updateCustomer(createdIndividual.id(), promoteReq);
        assertThat(promoted.customerTypeCode()).isEqualTo("BUSINESS");

        // 5. Invalid customerTypeCode -> 400 VALIDATION_FAILED
        CreateCustomerRequest invalidTypeReq =
                new CreateCustomerRequest(
                        "Khách Hàng Sai Loại",
                        nextSyntheticNationalId(),
                        "invalid.41@example.com",
                        "0904100003",
                        "10 Trần Phú",
                        "Đà Nẵng",
                        LocalDate.of(1990, 3, 15),
                        "PLATINUM_INVALID");

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalidTypeReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName(
            "AC3 & AC4: Effective limit min(account, customerType) enforces 100M cap on INDIVIDUAL,"
                    + " allows 150M on BUSINESS, caches customerTypes (1 SQL cold / 0 SQL warm),"
                    + " and evicts on PUT")
    void effectiveLimitsAndCacheEviction_shouldEnforceCustomerTypeCapsAndEvictCacheOnUpdate()
            throws Exception {
        // 1. Create an INDIVIDUAL customer and a BUSINESS customer
        CustomerResponse individualCustomer =
                customerService.createCustomer(
                        new CreateCustomerRequest(
                                "Khách Cá Nhân Hạn Mức",
                                nextSyntheticNationalId(),
                                "ind.limit@example.com",
                                "0904100011",
                                "Số 1 Hoàn Kiếm",
                                "Hà Nội",
                                LocalDate.of(1991, 4, 20),
                                "INDIVIDUAL"));

        CustomerResponse businessCustomer =
                customerService.createCustomer(
                        new CreateCustomerRequest(
                                "Doanh Nghiệp Hạn Mức",
                                nextSyntheticNationalId(),
                                "biz.limit@example.com",
                                "0904100012",
                                "Số 2 Quận 1",
                                "Hồ Chí Minh",
                                LocalDate.of(1980, 1, 1),
                                "BUSINESS"));

        // Both accounts set account-level transactionLimit = 300,000,000 and dailyLimit =
        // 800,000,000
        AccountResponse individualAccount =
                accountService.createAccount(
                        new CreateAccountRequest(
                                individualCustomer.id(),
                                new BigDecimal("300000000.00"),
                                new BigDecimal("800000000.00")));
        AccountResponse businessAccount =
                accountService.createAccount(
                        new CreateAccountRequest(
                                businessCustomer.id(),
                                new BigDecimal("300000000.00"),
                                new BigDecimal("800000000.00")));

        // Fund both accounts with 500,000,000 VND
        transactionService.deposit(
                new DepositRequest(
                        individualAccount.id(),
                        new BigDecimal("500000000.00"),
                        "Hà Nội",
                        "Fund individual account"));
        transactionService.deposit(
                new DepositRequest(
                        businessAccount.id(),
                        new BigDecimal("500000000.00"),
                        "Hồ Chí Minh",
                        "Fund business account"));

        // 2. INDIVIDUAL customer attempts to transfer 150,000,000 VND -> 422 LIMIT_EXCEEDED with
        // effective limit 100000000.00 (= min(300M, 100M))
        TransferRequest individualTransfer150M =
                new TransferRequest(
                        individualAccount.id(),
                        businessAccount.id(),
                        new BigDecimal("150000000.00"),
                        "Hà Nội",
                        "Transfer 150M from INDIVIDUAL");

        mockMvc.perform(
                        post("/api/v1/transactions/transfers")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(individualTransfer150M)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail", containsString("100000000.00")));

        // 3. BUSINESS customer transfers 150,000,000 VND -> 201 Created (effective limit =
        // min(300M, 2B) = 300M)
        TransferRequest businessTransfer150M =
                new TransferRequest(
                        businessAccount.id(),
                        individualAccount.id(),
                        new BigDecimal("150000000.00"),
                        "Hồ Chí Minh",
                        "Transfer 150M from BUSINESS");

        mockMvc.perform(
                        post("/api/v1/transactions/transfers")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(businessTransfer150M)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(150000000.00));

        // 4. Verify customerTypes cache cold (1 SQL) vs warm (0 SQL) per Rule 16
        Cache customerTypesCache = cacheManager.getCache(CacheConfig.CUSTOMER_TYPES_CACHE);
        assertThat(customerTypesCache).isNotNull();
        customerTypesCache.clear();

        hibernateStats.clear();
        mockMvc.perform(get("/api/v1/customer-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        assertThat(hibernateStats.getPrepareStatementCount())
                .as("Cold GET /api/v1/customer-types should execute exactly 1 SQL query")
                .isEqualTo(1L);
        assertThat(customerTypesCache.get("all")).isNotNull();

        hibernateStats.clear();
        mockMvc.perform(get("/api/v1/customer-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        assertThat(hibernateStats.getPrepareStatementCount())
                .as("Warm GET /api/v1/customer-types must hit cache with 0 SQL queries")
                .isZero();

        // 5. Admin updates INDIVIDUAL maxTransactionLimit to 200M -> cache evicted and 150M
        // transfer now succeeds! Repeat restoration in finally block to keep canonical limits for
        // other tests.
        try {
            UpdateCustomerTypeRequest raiseIndividualLimits =
                    new UpdateCustomerTypeRequest(
                            new BigDecimal("200000000.00"), new BigDecimal("600000000.00"));

            mockMvc.perform(
                            put("/api/v1/customer-types/INDIVIDUAL")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(raiseIndividualLimits)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.maxTransactionLimit").value(200000000.00))
                    .andExpect(jsonPath("$.maxDailyLimit").value(600000000.00));

            assertThat(customerTypesCache.get("all")).isNull();

            mockMvc.perform(
                            post("/api/v1/transactions/transfers")
                                    .header("Idempotency-Key", UUID.randomUUID().toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    individualTransfer150M)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("COMPLETED"));
        } finally {
            UpdateCustomerTypeRequest restoreCanonicalLimits =
                    new UpdateCustomerTypeRequest(
                            new BigDecimal("100000000.00"), new BigDecimal("500000000.00"));
            mockMvc.perform(
                            put("/api/v1/customer-types/INDIVIDUAL")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    restoreCanonicalLimits)))
                    .andExpect(status().isOk());
        }
    }
}
