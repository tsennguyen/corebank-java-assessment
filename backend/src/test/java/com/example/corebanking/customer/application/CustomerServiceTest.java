package com.example.corebanking.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.DuplicateResourceException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.ResourceNotFoundException;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerRequest;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerCodeGenerator;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerCodeGenerator customerCodeGenerator;
    @Mock private CustomerDeletionGuard customerDeletionGuard;

    private final Clock clock =
            Clock.fixed(Instant.parse("2026-10-09T08:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService =
                new CustomerService(
                        customerRepository, customerCodeGenerator, clock, customerDeletionGuard);
    }

    private void setId(Customer customer, Long id) {
        try {
            Field idField = Customer.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(customer, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("createCustomer successfully persists customer and masks CCCD")
    void createCustomer_shouldSucceed() {
        CreateCustomerRequest request =
                new CreateCustomerRequest(
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        when(customerRepository.existsByNationalId("000123456789")).thenReturn(false);
        when(customerCodeGenerator.nextCustomerCode()).thenReturn("CUS0000001");
        when(customerRepository.save(any(Customer.class)))
                .thenAnswer(
                        invocation -> {
                            Customer c = invocation.getArgument(0);
                            setId(c, 1L);
                            return c;
                        });

        CustomerResponse response = customerService.createCustomer(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.customerCode()).isEqualTo("CUS0000001");
        assertThat(response.fullName()).isEqualTo("Nguyen Van An");
        assertThat(response.nationalId()).isEqualTo("*********789");
        assertThat(response.location()).isEqualTo("Ha Noi");
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    @DisplayName("createCustomer throws DuplicateResourceException and masks national ID in logs")
    @ExtendWith(OutputCaptureExtension.class)
    void createCustomer_shouldThrowWhenNationalIdExists(CapturedOutput output) {
        CreateCustomerRequest request =
                new CreateCustomerRequest(
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));

        when(customerRepository.existsByNationalId("000123456789")).thenReturn(true);

        assertThatThrownBy(() -> customerService.createCustomer(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("Customer with national ID already exists");

        assertThat(output.getOut()).contains("*********789");
        assertThat(output.getOut()).doesNotContain("000123456789");
    }

    @Test
    @DisplayName("getCustomerById returns active customer with masked CCCD")
    void getCustomerById_shouldReturnCustomer() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));
        setId(customer, 1L);

        when(customerRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));

        CustomerResponse response = customerService.getCustomerById(1L);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.customerCode()).isEqualTo("CUS0000001");
        assertThat(response.nationalId()).isEqualTo("*********789");
    }

    @Test
    @DisplayName(
            "getCustomerById throws ResourceNotFoundException when customer missing or deleted")
    void getCustomerById_shouldThrowWhenNotFound() {
        when(customerRepository.findByIdAndDeletedAtIsNull(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getCustomerById(999L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer not found with identifier: 999");
    }

    @Test
    @DisplayName("searchCustomers successfully filters and returns paginated response")
    void searchCustomers_shouldReturnPageResponse() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));
        setId(customer, 1L);

        PageRequest pageRequest = PageRequest.of(0, 20, Sort.by("fullName").ascending());
        PageRequest effectivePageRequest =
                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("fullName"), Sort.Order.desc("id")));
        when(customerRepository.findAll(any(Specification.class), eq(effectivePageRequest)))
                .thenReturn(new PageImpl<>(List.of(customer), effectivePageRequest, 1));

        PageResponse<CustomerResponse> result =
                customerService.searchCustomers("van", "Ha Noi", pageRequest);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).fullName()).isEqualTo("Nguyen Van An");
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.page()).isEqualTo(0);
    }

    @Test
    @DisplayName("searchCustomers throws BusinessRuleException when sort field is not in whitelist")
    void searchCustomers_shouldRejectInvalidSortField() {
        PageRequest pageRequest = PageRequest.of(0, 20, Sort.by("password").ascending());

        assertThatThrownBy(() -> customerService.searchCustomers(null, null, pageRequest))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_SORT_FIELD))
                .hasMessageContaining("Invalid sort field 'password'");
    }

    @Test
    @DisplayName("searchCustomers throws BusinessRuleException when page size exceeds 100")
    void searchCustomers_shouldRejectSizeExceeding100() {
        PageRequest pageRequest = PageRequest.of(0, 101, Sort.by("id").ascending());

        assertThatThrownBy(() -> customerService.searchCustomers(null, null, pageRequest))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED))
                .hasMessageContaining("Page size must be between 1 and 100");
    }

    @Test
    @DisplayName("updateCustomer updates mutable fields when version matches")
    void updateCustomer_shouldSucceed() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));
        setId(customer, 1L);

        when(customerRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));
        when(customerRepository.saveAndFlush(any(Customer.class)))
                .thenAnswer(i -> i.getArgument(0));

        UpdateCustomerRequest updateReq =
                new UpdateCustomerRequest(
                        "Nguyen Van Binh",
                        "binh.nguyen@example.com",
                        "0900000002",
                        "456 Tran Hung Dao",
                        "Da Nang",
                        LocalDate.of(1992, 5, 20),
                        0L);

        CustomerResponse response = customerService.updateCustomer(1L, updateReq);

        assertThat(response.fullName()).isEqualTo("Nguyen Van Binh");
        assertThat(response.location()).isEqualTo("Da Nang");
        assertThat(response.email()).isEqualTo("binh.nguyen@example.com");
    }

    @Test
    @DisplayName(
            "updateCustomer throws BusinessRuleException with CONCURRENT_MODIFICATION on version"
                    + " mismatch")
    void updateCustomer_shouldThrowOnVersionMismatch() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));
        setId(customer, 1L);

        when(customerRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));

        UpdateCustomerRequest outdatedReq =
                new UpdateCustomerRequest(
                        "Nguyen Van Binh",
                        "binh.nguyen@example.com",
                        "0900000002",
                        "456 Tran Hung Dao",
                        "Da Nang",
                        LocalDate.of(1992, 5, 20),
                        5L); // Expected is 0L, passed 5L

        assertThatThrownBy(() -> customerService.updateCustomer(1L, outdatedReq))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION))
                .hasMessageContaining(
                        "Customer has been modified by another concurrent transaction");
    }

    @Test
    @DisplayName("deleteCustomer marks customer with deletion timestamp")
    void deleteCustomer_shouldSoftDelete() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));
        setId(customer, 1L);

        when(customerRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));

        customerService.deleteCustomer(1L);

        assertThat(customer.isDeleted()).isTrue();
        assertThat(customer.getDeletedAt()).isEqualTo(clock.instant());
        verify(customerRepository).save(customer);
    }

    @Test
    @DisplayName("deleteCustomer throws CUSTOMER_HAS_ACTIVE_ACCOUNTS when guard rejects deletion")
    void deleteCustomer_shouldThrowWhenCustomerHasActiveAccounts() {
        Customer customer =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an.nguyen@example.com",
                        "0900000001",
                        "123 Pho Hue",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 15));
        setId(customer, 1L);

        when(customerRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(customer));
        org.mockito.Mockito.doThrow(
                        new BusinessRuleException(
                                ErrorCode.CUSTOMER_HAS_ACTIVE_ACCOUNTS,
                                "Customer still has non-closed accounts and cannot be deleted"))
                .when(customerDeletionGuard)
                .validateCanDeleteCustomer(1L);

        assertThatThrownBy(() -> customerService.deleteCustomer(1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CUSTOMER_HAS_ACTIVE_ACCOUNTS))
                .hasMessageContaining("Customer still has non-closed accounts");

        org.mockito.Mockito.verify(customerRepository, org.mockito.Mockito.never())
                .save(any(Customer.class));
    }
}
