package com.example.corebanking.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-stack integration test verifying recurring transaction setup, validation rules, lifecycle
 * state machine ({@code ACTIVE -> PAUSED -> ACTIVE -> CANCELLED}), account closure guard, SQL query
 * count ({@code <= 2}), and partial index {@code idx_rec_due} (Story 3.5: L2-06, FR-16).
 */
@AutoConfigureMockMvc
class RecurringTransactionSetupIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private Clock clock;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcClient jdbcClient;

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName(
            "Recurring transaction setup, lifecycle transitions, account closure guard, and"
                    + " SQL query count work end-to-end (L2-06, FR-16)")
    void recurringTransactionSetup_lifecycleAndAccountClosureGuard_shouldWorkEndToEnd()
            throws Exception {
        Customer customer =
                customerRepository.save(
                        new Customer(
                                "CUS9305001",
                                "Nguyễn Văn An",
                                "000305000001",
                                "an.rec35@example.com",
                                "0900003501",
                                "Số 10 Đường Mẫu A",
                                "Hà Nội",
                                LocalDate.of(1990, 5, 10)));

        LocalDate today = LocalDate.now(clock);
        Account sourceAccount =
                accountRepository.save(
                        new Account(
                                "100930500016",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                today));
        Account targetAccount =
                accountRepository.save(
                        new Account(
                                "100930500024",
                                customer.getId(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("200000000.00"),
                                today));

        // 1. Create TRANSFER recurring transaction starting today -> 201 Created, status=ACTIVE
        MvcResult createResult =
                mockMvc.perform(
                                post("/api/v1/recurring-transactions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"type\":\"TRANSFER\",\"sourceAccountId\":"
                                                        + sourceAccount.getId()
                                                        + ",\"targetAccountId\":"
                                                        + targetAccount.getId()
                                                        + ",\"amount\":2500000.00,\"frequency\":\"MONTHLY\",\"startDate\":\""
                                                        + today
                                                        + "\",\"endDate\":\""
                                                        + today.plusMonths(6)
                                                        + "\",\"description\":\"Chuyển khoản tiết"
                                                        + " kiệm hàng tháng\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.type").value("TRANSFER"))
                        .andExpect(jsonPath("$.frequency").value("MONTHLY"))
                        .andExpect(jsonPath("$.amount").value(2500000.00))
                        .andExpect(jsonPath("$.createdBy").value("admin"))
                        .andReturn();

        JsonNode createdJson =
                objectMapper.readTree(createResult.getResponse().getContentAsString());
        Long recurringId = createdJson.get("id").asLong();

        // 2. Create PAYMENT recurring transaction starting in future -> nextRunAt at 00:00 VN time
        LocalDate futureStart = today.plusDays(10);
        mockMvc.perform(
                        post("/api/v1/recurring-transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\":\"PAYMENT\",\"sourceAccountId\":"
                                                + sourceAccount.getId()
                                                + ",\"amount\":800000.00,\"frequency\":\"MONTHLY\",\"startDate\":\""
                                                + futureStart
                                                + "\",\"description\":\"Thanh toán tiền điện\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(
                        jsonPath("$.nextRunAt")
                                .value(
                                        futureStart
                                                .atStartOfDay(clock.getZone())
                                                .toInstant()
                                                .toString()));

        // 3. Reject invalid requests: endDate < startDate (400), same account transfer (400)
        mockMvc.perform(
                        post("/api/v1/recurring-transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\":\"PAYMENT\",\"sourceAccountId\":"
                                                + sourceAccount.getId()
                                                + ",\"amount\":500000.00,\"frequency\":\"MONTHLY\",\"startDate\":\""
                                                + futureStart
                                                + "\",\"endDate\":\""
                                                + today
                                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(
                        post("/api/v1/recurring-transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"type\":\"TRANSFER\",\"sourceAccountId\":"
                                                + sourceAccount.getId()
                                                + ",\"targetAccountId\":"
                                                + sourceAccount.getId()
                                                + ",\"amount\":500000.00,\"frequency\":\"MONTHLY\",\"startDate\":\""
                                                + today
                                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));

        // 4. Reject closing sourceAccount or targetAccount while ACTIVE recurring exists -> 409
        mockMvc.perform(delete("/api/v1/accounts/{id}", sourceAccount.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_ACTIVE_RECURRING"));

        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", targetAccount.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"targetStatus\":\"CLOSED\",\"reason\":\"Đóng tài khoản"
                                                + " đích\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_HAS_ACTIVE_RECURRING"));

        // 5. Search recurring transactions by accountId and status with HibernateStatistics <= 2
        // SQL
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(
                        get("/api/v1/recurring-transactions")
                                .param("status", "ACTIVE")
                                .param("accountId", sourceAccount.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        assertThat(statistics.getPrepareStatementCount())
                .as("GET /api/v1/recurring-transactions must execute <= 2 SQL queries (Rule 16)")
                .isLessThanOrEqualTo(2L);

        // 6. Pause -> Resume -> Cancel lifecycle on recurringId
        mockMvc.perform(post("/api/v1/recurring-transactions/{id}/pause", recurringId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        // If targetAccount is LOCKED while recurring is PAUSED, resume must reject with 422
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", targetAccount.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"targetStatus\":\"LOCKED\",\"reason\":\"Tạm khóa kiểm"
                                                + " tra\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/recurring-transactions/{id}/resume", recurringId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        // Unlock targetAccount -> resume succeeds (200 OK, status=ACTIVE)
        mockMvc.perform(
                        post("/api/v1/accounts/{id}/status", targetAccount.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetStatus\":\"ACTIVE\",\"reason\":\"Mở khóa\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/recurring-transactions/{id}/resume", recurringId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Cancel recurringId -> 204 No Content, status=CANCELLED
        mockMvc.perform(delete("/api/v1/recurring-transactions/{id}", recurringId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/recurring-transactions/{id}", recurringId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // Resuming a CANCELLED recurring transaction is rejected with 409
        mockMvc.perform(post("/api/v1/recurring-transactions/{id}/resume", recurringId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        // 7. Once recurringId is CANCELLED, targetAccount has no remaining ACTIVE recurring ->
        // closing targetAccount now succeeds!
        mockMvc.perform(delete("/api/v1/accounts/{id}", targetAccount.getId()))
                .andExpect(status().isNoContent());

        Account reloadedTarget = accountRepository.findById(targetAccount.getId()).orElseThrow();
        assertThat(reloadedTarget.getStatus()).isEqualTo(AccountStatus.CLOSED);

        // 8. Verify EXPLAIN plan for due recurring query uses idx_rec_due when seqscan disabled
        jdbcClient.sql("SET LOCAL enable_seqscan = off").update();
        List<String> explainLines =
                jdbcClient
                        .sql(
                                "EXPLAIN SELECT * FROM core.recurring_transaction WHERE status ="
                                        + " 'ACTIVE' AND next_run_at <= CURRENT_TIMESTAMP ORDER BY"
                                        + " next_run_at ASC, id ASC LIMIT 50")
                        .query(String.class)
                        .list();
        String planText = String.join("\n", explainLines);
        assertThat(planText).contains("idx_rec_due");
    }
}
