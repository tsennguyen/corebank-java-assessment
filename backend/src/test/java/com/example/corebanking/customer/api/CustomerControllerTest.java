package com.example.corebanking.customer.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.corebanking.common.api.GlobalExceptionHandler;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.api.RequestIdFilter;
import com.example.corebanking.common.config.ClockConfig;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.DuplicateResourceException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerRequest;
import com.example.corebanking.customer.application.CustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@WebMvcTest(CustomerController.class)
@Import({ClockConfig.class, GlobalExceptionHandler.class, RequestIdFilter.class})
class CustomerControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private CustomerService customerService;

    @Test
    @DisplayName("POST /api/v1/customers returns 201 with Location header and masked CCCD")
    void createCustomer_shouldReturn201WithLocationHeader() throws Exception {
        CreateCustomerRequest request =
                new CreateCustomerRequest(
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        CustomerResponse response =
                new CustomerResponse(
                        1L,
                        "CUS0000001",
                        "Nguyen Van An",
                        "*********789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15),
                        Instant.now(),
                        Instant.now(),
                        0L);

        when(customerService.createCustomer(any(CreateCustomerRequest.class))).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/customers/1")))
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.customerCode").value("CUS0000001"))
                .andExpect(jsonPath("$.nationalId").value("*********789"))
                .andExpect(jsonPath("$.location").value("Ha Noi"));
    }

    @Test
    @DisplayName("POST /api/v1/customers with invalid national ID returns 400 VALIDATION_FAILED")
    void createCustomer_shouldReturn400WhenNationalIdInvalid() throws Exception {
        // Invalid national ID (does not start with 000)
        CreateCustomerRequest invalidRequest =
                new CreateCustomerRequest(
                        "Nguyen Van An",
                        "123456789012",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("nationalId"));
    }

    @Test
    @DisplayName("POST /api/v1/customers with duplicate national ID returns 409 DUPLICATE_RESOURCE")
    void createCustomer_shouldReturn409WhenDuplicate() throws Exception {
        CreateCustomerRequest request =
                new CreateCustomerRequest(
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        when(customerService.createCustomer(any(CreateCustomerRequest.class)))
                .thenThrow(
                        new DuplicateResourceException("Customer with national ID already exists"));

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.title").value("Resource already exists"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/{id} returns 200 with customer details")
    void getCustomerById_shouldReturn200() throws Exception {
        CustomerResponse response =
                new CustomerResponse(
                        1L,
                        "CUS0000001",
                        "Nguyen Van An",
                        "*********789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15),
                        Instant.now(),
                        Instant.now(),
                        0L);

        when(customerService.getCustomerById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/customers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.customerCode").value("CUS0000001"))
                .andExpect(jsonPath("$.nationalId").value("*********789"));
    }

    @Test
    @DisplayName("GET /api/v1/customers/{id} returns 404 when customer not found")
    void getCustomerById_shouldReturn404WhenNotFound() throws Exception {
        when(customerService.getCustomerById(999L))
                .thenThrow(new ResourceNotFoundException("Customer", 999L));

        mockMvc.perform(get("/api/v1/customers/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/customers returns 200 with PageResponse envelope")
    void searchCustomers_shouldReturn200() throws Exception {
        CustomerResponse response =
                new CustomerResponse(
                        1L,
                        "CUS0000001",
                        "Nguyen Van An",
                        "*********789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15),
                        Instant.now(),
                        Instant.now(),
                        0L);

        PageResponse<CustomerResponse> pageResponse =
                new PageResponse<>(List.of(response), 0, 20, 1L, 1);

        when(customerService.searchCustomers(eq("van"), eq("Ha Noi"), any(Pageable.class)))
                .thenReturn(pageResponse);

        mockMvc.perform(
                        get("/api/v1/customers")
                                .param("name", "van")
                                .param("location", "Ha Noi")
                                .param("page", "0")
                                .param("size", "20")
                                .param("sort", "fullName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].customerCode").value("CUS0000001"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1L));
    }

    @Test
    @DisplayName("GET /api/v1/customers with invalid sort field returns 400 INVALID_SORT_FIELD")
    void searchCustomers_shouldReturn400OnInvalidSort() throws Exception {
        when(customerService.searchCustomers(any(), any(), any(Pageable.class)))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.INVALID_SORT_FIELD, "Invalid sort field 'unsupported'"));

        mockMvc.perform(get("/api/v1/customers").param("sort", "unsupported,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT_FIELD"));
    }

    @Test
    @DisplayName(
            "PUT /api/v1/customers/{id} with mismatched version returns 409"
                    + " CONCURRENT_MODIFICATION")
    void updateCustomer_shouldReturn409OnVersionMismatch() throws Exception {
        UpdateCustomerRequest updateReq =
                new UpdateCustomerRequest(
                        "Nguyen Van Binh",
                        "binh.nguyen@example.com",
                        "0900000002",
                        "456 Tran Hung Dao",
                        "Da Nang",
                        LocalDate.of(1992, 5, 20),
                        5L);

        when(customerService.updateCustomer(eq(1L), any(UpdateCustomerRequest.class)))
                .thenThrow(
                        new BusinessRuleException(
                                ErrorCode.CONCURRENT_MODIFICATION,
                                "Customer has been modified by another concurrent transaction"));

        mockMvc.perform(
                        put("/api/v1/customers/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    @DisplayName("PUT /api/v1/customers/{id} with valid version returns 200 OK")
    void updateCustomer_shouldReturn200OnSuccess() throws Exception {
        UpdateCustomerRequest updateReq =
                new UpdateCustomerRequest(
                        "Nguyen Van Binh",
                        "binh.nguyen@example.com",
                        "0900000002",
                        "456 Tran Hung Dao",
                        "Da Nang",
                        LocalDate.of(1992, 5, 20),
                        0L);

        CustomerResponse updatedResponse =
                new CustomerResponse(
                        1L,
                        "CUS0000001",
                        "Nguyen Van Binh",
                        "*********789",
                        "binh.nguyen@example.com",
                        "0900000002",
                        "456 Tran Hung Dao",
                        "Da Nang",
                        LocalDate.of(1992, 5, 20),
                        Instant.now(),
                        Instant.now(),
                        1L);

        when(customerService.updateCustomer(eq(1L), any(UpdateCustomerRequest.class)))
                .thenReturn(updatedResponse);

        mockMvc.perform(
                        put("/api/v1/customers/1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nguyen Van Binh"))
                .andExpect(jsonPath("$.version").value(1L));
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/{id} returns 204 No Content")
    void deleteCustomer_shouldReturn204() throws Exception {
        doNothing().when(customerService).deleteCustomer(1L);

        mockMvc.perform(delete("/api/v1/customers/1")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/customers/{id} returns 404 when customer not found")
    void deleteCustomer_shouldReturn404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("Customer", 999L))
                .when(customerService)
                .deleteCustomer(999L);

        mockMvc.perform(delete("/api/v1/customers/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
