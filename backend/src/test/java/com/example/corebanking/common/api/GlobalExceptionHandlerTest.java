package com.example.corebanking.common.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.auth.infrastructure.SecurityConfig;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.DuplicateResourceException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(controllers = GlobalExceptionHandlerTest.TestErrorController.class)
@Import({
    GlobalExceptionHandlerTest.TestErrorController.class,
    GlobalExceptionHandler.class,
    RequestIdFilter.class,
    ClockConfig.class,
    SecurityConfig.class
})
@WithMockUser(roles = "ADMIN")
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("should return 400 ProblemDetail with VALIDATION_FAILED when payload invalid")
    void validationError_returns400WithErrorsList() throws Exception {
        String invalidBody =
                """
                {
                    "name": "",
                    "amount": -100
                }
                """;

        mockMvc.perform(
                        post("/api/v1/test-errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/test-errors/validation"))
                .andExpect(jsonPath("$.errors", hasSize(2)));
    }

    @Test
    @DisplayName("should return 400 ProblemDetail when JSON request body is malformed")
    void malformedJson_returns400ProblemDetail() throws Exception {
        mockMvc.perform(
                        post("/api/v1/test-errors/validation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ unclosed-json-data"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Malformed JSON request body"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("should return 422 ProblemDetail when BusinessRuleException is thrown")
    void businessRuleException_returns422WithErrorCode() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/business-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:limit-exceeded"))
                .andExpect(jsonPath("$.title").value("Transaction limit exceeded"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "Amount 150000000.00 exceeds effective per-transaction"
                                                + " limit 100000000.00"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("should return 404 ProblemDetail when ResourceNotFoundException is thrown")
    void resourceNotFoundException_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:resource-not-found"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        containsString(
                                                "Account not found with identifier: ACC-999")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("should return 409 ProblemDetail when DuplicateResourceException is thrown")
    void duplicateResourceException_returns409() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:duplicate-resource"))
                .andExpect(jsonPath("$.detail").value("Account number already exists"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("should return 409 ProblemDetail when CannotAcquireLockException is thrown")
    void cannotAcquireLockException_returns409WithLockTimeout() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/lock-timeout"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:lock-timeout"))
                .andExpect(jsonPath("$.title").value("Resource lock acquisition timed out"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "Resource lock acquisition timed out. Please retry the"
                                                + " transaction."))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("should return 500 ProblemDetail without leaking stack trace on unexpected errors")
    void unexpectedException_returns500AndMasksInternalDetails() throws Exception {
        mockMvc.perform(get("/api/v1/test-errors/internal-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:problem-type:internal-error"))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(not(containsString("Sensitive database stack trace"))))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        containsString(
                                                "An unexpected error occurred. Please contact"
                                                        + " support with request ID:")))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("should preserve incoming X-Request-Id header in response and ProblemDetail")
    void customRequestIdHeader_isPreservedInResponseAndProblemDetail() throws Exception {
        String customId = "client-custom-req-777";

        mockMvc.perform(
                        get("/api/v1/test-errors/business-rule")
                                .header(RequestIdFilter.REQUEST_ID_HEADER, customId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, customId))
                .andExpect(jsonPath("$.requestId").value(customId));
    }

    @RestController
    @RequestMapping("/api/v1/test-errors")
    static class TestErrorController {

        @PostMapping("/validation")
        public String testValidation(@Valid @RequestBody DummyRequest request) {
            return "OK";
        }

        @GetMapping("/business-rule")
        public String testBusinessRule() {
            throw new BusinessRuleException(
                    ErrorCode.LIMIT_EXCEEDED,
                    "Amount 150000000.00 exceeds effective per-transaction limit 100000000.00");
        }

        @GetMapping("/not-found")
        public String testNotFound() {
            throw new ResourceNotFoundException("Account", "ACC-999");
        }

        @GetMapping("/duplicate")
        public String testDuplicate() {
            throw new DuplicateResourceException("Account number already exists");
        }

        @GetMapping("/lock-timeout")
        public String testLockTimeout() {
            throw new CannotAcquireLockException("canceling statement due to lock timeout");
        }

        @GetMapping("/internal-error")
        public String testInternalError() {
            throw new RuntimeException("Sensitive database stack trace details");
        }
    }

    record DummyRequest(
            @NotBlank(message = "Field name cannot be blank") String name,
            @Positive(message = "must be greater than 0") Long amount) {}
}
