package com.example.corebanking.auth;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end PostgreSQL Testcontainers integration test verifying Customer Self-Service ({@code
 * /api/v1/me}, {@code /api/v1/me/accounts}) and OwnershipGuard IDOR protection across Account and
 * Transaction endpoints (Story 3.2: L2-01, FR-14, NFR-5).
 */
@AutoConfigureMockMvc
class OwnershipIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcClient jdbcClient;

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\""
                                                        + username
                                                        + "\",\"password\":\""
                                                        + password
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        JsonNode json = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return json.get("accessToken").asText();
    }

    @Test
    @Transactional
    @DisplayName(
            "Customer self-service and OwnershipGuard prevent IDOR between Customer A, Customer B,"
                    + " and Admin (AC 1–5, L2-01)")
    void customerSelfServiceAndOwnershipGuard_shouldPreventCrossCustomerAccessAndAllowAdmin()
            throws Exception {
        String seedSql =
                new ClassPathResource("db/seed/R__demo_seed.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        jdbcClient.sql(seedSql).update();

        String adminToken = loginAndGetToken("admin", "Admin@123");
        String tokenAn = loginAndGetToken("cust.an", "Customer@123");
        String tokenBinh = loginAndGetToken("cust.binh", "Customer@123");

        Long accountAnId =
                jdbcClient
                        .sql("SELECT id FROM core.account WHERE account_number = '100800000018'")
                        .query(Long.class)
                        .single();
        Long accountBinhId =
                jdbcClient
                        .sql("SELECT id FROM core.account WHERE account_number = '100800000026'")
                        .query(Long.class)
                        .single();

        // 1. AC 1: GET /api/v1/me returns Customer A's own profile with masked nationalId
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerCode").value("CUS8000001"))
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn An"))
                .andExpect(jsonPath("$.nationalId").value("*********001"));

        // Admin calling /api/v1/me or /api/v1/me/accounts receives 403 ACCESS_DENIED
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(
                        get("/api/v1/me/accounts")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 2. AC 2: GET /api/v1/me/accounts returns only Customer A's accounts, never Customer B's
        mockMvc.perform(
                        get("/api/v1/me/accounts")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accountNumber").value("100800000018"));

        mockMvc.perform(
                        get("/api/v1/me/accounts")
                                .param("status", "LOCKED")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 3. AC 3: Customer A can view own account & transactions, but gets 403 on Customer B's
        mockMvc.perform(
                        get("/api/v1/accounts/" + accountAnId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("100800000018"));

        mockMvc.perform(
                        get("/api/v1/accounts/" + accountAnId + "/transactions")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/accounts/" + accountBinhId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        get("/api/v1/accounts/" + accountBinhId + "/transactions")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 4. AC 4: Transaction ownership (source OR target account) and filtered search
        Long txAnToBinhId =
                jdbcClient
                        .sql(
                                "INSERT INTO core.transaction (reference_no, type,"
                                    + " source_account_id, target_account_id, amount, fee,"
                                    + " source_balance_after, target_balance_after,"
                                    + " system_balance_delta, location, description, status,"
                                    + " created_by, created_at) VALUES ('TXN-TEST-AN-BINH',"
                                    + " 'TRANSFER', :srcId, :tgtId, 100000.00, 0.00, 3400000.00,"
                                    + " 7300000.00, 0.00, 'Hà Nội', 'Chuyển khoản An sang Bình',"
                                    + " 'COMPLETED', 'admin', CURRENT_TIMESTAMP) RETURNING id")
                        .param("srcId", accountAnId)
                        .param("tgtId", accountBinhId)
                        .query(Long.class)
                        .single();
        Long txBinhOnlyId =
                jdbcClient
                        .sql(
                                "SELECT id FROM core.transaction WHERE reference_no ="
                                        + " 'TXN-SEED-0011'")
                        .query(Long.class)
                        .single();

        // Both An (source) and Binh (target) can access the transfer TXN-TEST-AN-BINH
        mockMvc.perform(
                        get("/api/v1/transactions/" + txAnToBinhId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNo").value("TXN-TEST-AN-BINH"));

        mockMvc.perform(
                        get("/api/v1/transactions/" + txAnToBinhId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBinh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceNo").value("TXN-TEST-AN-BINH"));

        // An cannot access Binh's private payment TXN-SEED-0011 -> 403 ACCESS_DENIED
        mockMvc.perform(
                        get("/api/v1/transactions/" + txBinhOnlyId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // An filtering GET /api/v1/transactions?accountId={accountBinhId} -> 403 ACCESS_DENIED
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .param("accountId", String.valueOf(accountBinhId))
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // An calling GET /api/v1/transactions without accountId gets only transactions of An's
        // account (TXN-SEED-0007, TXN-SEED-0010, and TXN-TEST-AN-BINH = 3 transactions)
        mockMvc.perform(
                        get("/api/v1/transactions")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));

        // 5. AC 5: Admin can access all accounts and transactions; Customer gets 403 on
        // GET /api/v1/accounts and write endpoints
        mockMvc.perform(
                        get("/api/v1/accounts")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        patch("/api/v1/accounts/" + accountAnId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"transactionLimit\":10000000,\"dailyLimit\":50000000,\"version\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        delete("/api/v1/transactions/" + txAnToBinhId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenAn))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        get("/api/v1/accounts")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/accounts/" + accountBinhId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/transactions/" + txBinhOnlyId)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
