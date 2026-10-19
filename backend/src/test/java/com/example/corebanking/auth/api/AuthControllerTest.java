package com.example.corebanking.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.api.dto.LoginRequest;
import com.example.corebanking.auth.api.dto.TokenResponse;
import com.example.corebanking.auth.application.AuthService;
import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.domain.Role;
import com.example.corebanking.auth.infrastructure.JwtTokenService;
import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.customer.api.CustomerController;
import com.example.corebanking.customer.application.CustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AuthController.class, CustomerController.class})
@Import({
    ClockConfig.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    SecurityConfig.class,
    JwtTokenService.class
})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenService jwtTokenService;

    @MockitoBean private AuthService authService;
    @MockitoBean private CustomerService customerService;

    @Test
    @DisplayName(
            "POST /api/v1/auth/login is publicly accessible and returns 200 OK with JWT"
                    + " TokenResponse")
    void login_whenValid_shouldReturn200OkWithTokenResponse() throws Exception {
        LoginRequest request = new LoginRequest("admin", "Admin@123");
        assertThat(request.toString()).doesNotContain("Admin@123").contains("***");

        when(authService.login(any(LoginRequest.class)))
                .thenReturn(
                        TokenResponse.bearer("eyJhbGciOiJIUzI1NiJ9.test", 900L, Role.ADMIN, null));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("eyJhbGciOiJIUzI1NiJ9.test"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.customerId").isEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login returns 401 ProblemDetail when credentials are invalid")
    void login_whenInvalidCredentials_shouldReturn401ProblemDetail() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.UNAUTHORIZED, AuthService.INVALID_CREDENTIALS_MESSAGE));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login returns 400 VALIDATION_FAILED when fields are blank")
    void login_whenBlankFields_shouldReturn400ValidationFailed() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName(
            "Protected endpoint without token or with invalid JWT signature returns 401"
                    + " UNAUTHORIZED ProblemDetail")
    void protectedEndpoint_withoutTokenOrInvalidToken_shouldReturn401ProblemDetail()
            throws Exception {
        mockMvc.perform(get("/api/v1/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(
                        get("/api/v1/customers")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer invalid.tampered.jwt.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName(
            "ADMIN-only endpoint called with CUSTOMER JWT returns 403 ACCESS_DENIED ProblemDetail,"
                    + " while ADMIN JWT succeeds")
    void adminEndpoint_withCustomerJwt_shouldReturn403ProblemDetail_andWithAdminJwtSucceeds()
            throws Exception {
        AppUser customer = AppUser.createCustomer("cust.an", "$2a$10$hash", 10L);
        ReflectionTestUtils.setField(customer, "id", 2L);
        String customerToken = jwtTokenService.generateAccessToken(customer);

        mockMvc.perform(
                        get("/api/v1/customers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
                .andExpect(status().isForbidden())
                .andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        AppUser admin = AppUser.createAdmin("admin", "$2a$10$hash");
        ReflectionTestUtils.setField(admin, "id", 1L);
        String adminToken = jwtTokenService.generateAccessToken(admin);

        mockMvc.perform(
                        get("/api/v1/customers")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }
}
