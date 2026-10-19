package com.example.corebanking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.api.AuthController;
import com.example.corebanking.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full-stack integration test verifying opaque 256-bit refresh token issuance, {@code HttpOnly}
 * cookie attributes, one-time refresh token rotation, reuse detection revoking the entire token
 * family ({@code 401 TOKEN_REUSED}), session logout ({@code 204 No Content}), and brute-force login
 * rate limiting ({@code 429 TOO_MANY_REQUESTS} + {@code Retry-After}) against PostgreSQL 17
 * Testcontainers (Story 3.8: NC-07, FR-15, NFR-5).
 */
@AutoConfigureMockMvc
class RefreshTokenAndRateLimitIT extends IntegrationTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @Transactional
    @DisplayName(
            "Refresh token rotation R1 -> R2, reuse detection (401 TOKEN_REUSED) revoking family,"
                    + " and logout revocation work end-to-end (AC 1, AC 2, AC 3)")
    void refreshTokenRotation_reuseDetection_andLogout_shouldWorkEndToEnd() throws Exception {
        // 1. Login returns refreshToken in JSON body + HttpOnly Secure SameSite=Strict Set-Cookie
        MvcResult loginResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .header("X-Forwarded-For", "203.0.113.10")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                        .andExpect(
                                header().string(
                                                HttpHeaders.SET_COOKIE,
                                                containsString("refresh_token=")))
                        .andExpect(
                                header().string(
                                                HttpHeaders.SET_COOKIE,
                                                containsString("Path=/api/v1/auth")))
                        .andExpect(
                                header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                        .andExpect(
                                header().string(HttpHeaders.SET_COOKIE, containsString("Secure")))
                        .andExpect(
                                header().string(
                                                HttpHeaders.SET_COOKIE,
                                                containsString("SameSite=Strict")))
                        .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String r1 = loginJson.get("refreshToken").asText();
        assertThat(r1).isNotBlank();

        // 2. Rotate R1 -> R2 via Cookie (simulating browser silent refresh)
        MvcResult refreshResult =
                mockMvc.perform(
                                post("/api/v1/auth/refresh")
                                        .cookie(
                                                new Cookie(
                                                        AuthController.REFRESH_TOKEN_COOKIE_NAME,
                                                        r1)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.accessToken").isNotEmpty())
                        .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                        .andExpect(
                                header().string(
                                                HttpHeaders.SET_COOKIE,
                                                containsString("refresh_token=")))
                        .andReturn();

        JsonNode refreshJson =
                objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String r2 = refreshJson.get("refreshToken").asText();
        assertThat(r2).isNotBlank().isNotEqualTo(r1);

        // 3. Replay already-used R1 -> 401 TOKEN_REUSED + clears cookie + revokes entire family
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("TOKEN_REUSED"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        // 4. Subsequent attempt to use R2 (from the same family) is rejected with 401 UNAUTHORIZED
        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + r2 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        // 5. Fresh login -> logout revokes session and clears cookie (204 No Content)
        MvcResult secondLogin =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .header("X-Forwarded-For", "203.0.113.11")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        String r3 =
                objectMapper
                        .readTree(secondLogin.getResponse().getContentAsString())
                        .get("refreshToken")
                        .asText();

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .cookie(new Cookie(AuthController.REFRESH_TOKEN_COOKIE_NAME, r3)))
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"" + r3 + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @Transactional
    @DisplayName(
            "5 consecutive failed logins trigger 429 TOO_MANY_REQUESTS with Retry-After header on"
                    + " the 6th attempt (AC 4)")
    void loginRateLimiter_afterFiveFailedAttempts_shouldReturn429WithRetryAfterHeader()
            throws Exception {
        String attackerIp = "198.51.100.77";

        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(
                            post("/api/v1/auth/login")
                                    .header("X-Forwarded-For", attackerIp)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"username\":\"admin\",\"password\":\"WrongPass\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        // 6th attempt from same (username, clientIp) is blocked with 429 + Retry-After header
        MvcResult rateLimitedResult =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .header("X-Forwarded-For", attackerIp)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                        .andExpect(status().isTooManyRequests())
                        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                        .andExpect(jsonPath("$.status").value(429))
                        .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                        .andReturn();

        String retryAfter = rateLimitedResult.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(Long.parseLong(retryAfter)).isBetween(1L, 300L);
    }
}
