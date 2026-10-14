package com.example.corebanking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.domain.Role;
import com.example.corebanking.auth.infrastructure.AppUserRepository;
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
 * Full-stack integration test verifying Flyway V7 {@code core.app_user}, {@code
 * AdminBootstrapRunner}, {@code R__demo_seed.sql} customer users, JWT HS256 login, and role-based
 * access control across public, CUSTOMER, and ADMIN endpoints (Story 3.1: L2-01, L2-02, FR-13,
 * FR-14, NFR-5).
 */
@AutoConfigureMockMvc
class SecurityIntegrationTest extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private JdbcClient jdbcClient;

    @Test
    @Transactional
    @DisplayName(
            "Admin bootstrap, demo CUSTOMER seed login, and ADMIN vs CUSTOMER role access control"
                    + " work end-to-end (L2-01, L2-02)")
    void fullJwtLoginAndRoleBasedAccessControl_shouldEnforceSecurityInvariants() throws Exception {
        // 1. Public endpoints require no token
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());

        // 2. Unauthenticated call to protected API returns 401 UNAUTHORIZED ProblemDetail
        mockMvc.perform(get("/api/v1/statistics/balance-tiers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 3. Login with bootstrapped admin account succeeds and updates lastLoginAt
        MvcResult adminLoginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tokenType").value("Bearer"))
                        .andExpect(jsonPath("$.expiresIn").value(900))
                        .andExpect(jsonPath("$.role").value("ADMIN"))
                        .andExpect(jsonPath("$.customerId").isEmpty())
                        .andReturn();

        JsonNode adminTokenJson =
                objectMapper.readTree(adminLoginResult.getResponse().getContentAsString());
        String adminToken = adminTokenJson.get("accessToken").asText();
        assertThat(adminToken).isNotBlank();

        AppUser adminUser = appUserRepository.findByUsername("admin").orElseThrow();
        assertThat(adminUser.getRole()).isEqualTo(Role.ADMIN);
        assertThat(adminUser.getLastLoginAt()).isNotNull();

        // 4. Execute R__demo_seed.sql and login with seeded demo customer 'cust.an'
        String seedSql =
                new ClassPathResource("db/seed/R__demo_seed.sql")
                        .getContentAsString(StandardCharsets.UTF_8);
        jdbcClient.sql(seedSql).update();

        MvcResult customerLoginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"cust.an\",\"password\":\"Customer@123\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tokenType").value("Bearer"))
                        .andExpect(jsonPath("$.role").value("CUSTOMER"))
                        .andExpect(jsonPath("$.customerId").isNumber())
                        .andReturn();

        JsonNode customerTokenJson =
                objectMapper.readTree(customerLoginResult.getResponse().getContentAsString());
        String customerToken = customerTokenJson.get("accessToken").asText();

        // 5. Unknown username and wrong password return identical 401 UNAUTHORIZED message
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"unknown.user\",\"password\":\"Customer@123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"cust.an\",\"password\":\"Wrong@123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));

        // 6. CUSTOMER token receives 403 ACCESS_DENIED on ADMIN endpoints (write + admin read)
        mockMvc.perform(
                        get("/api/v1/customers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        get("/api/v1/statistics/balance-tiers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(
                        post("/api/v1/customers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // 7. CUSTOMER token can call shared authenticated read endpoint GET /api/v1/fees/preview
        mockMvc.perform(
                        get("/api/v1/fees/preview")
                                .param("type", "TRANSFER")
                                .param("amount", "1000000.00")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isOk());

        // 8. ADMIN token can call ADMIN endpoints
        mockMvc.perform(
                        get("/api/v1/statistics/balance-tiers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 9. Database CHECK constraint chk_app_user_customer_link rejects invalid role/customer_id
        assertThatThrownBy(
                        () ->
                                jdbcClient
                                        .sql(
                                                "INSERT INTO core.app_user (username,"
                                                    + " password_hash, role, customer_id) VALUES"
                                                    + " ('bad.customer', 'hash', 'CUSTOMER', NULL)")
                                        .update())
                .isInstanceOf(Exception.class);
    }
}
