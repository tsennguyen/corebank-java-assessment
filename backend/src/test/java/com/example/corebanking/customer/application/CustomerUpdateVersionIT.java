package com.example.corebanking.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.account.api.dto.UpdateAccountLimitsRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.api.dto.UpdateCustomerRequest;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import com.example.corebanking.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerUpdateVersionIT extends IntegrationTestBase {

    @Autowired private CustomerService customerService;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private Clock clock;

    @Test
    @DisplayName("R1: Updating customer returns incremented version and allows subsequent updates")
    void updateCustomer_shouldReturnIncrementedVersionAndAllowSubsequentUpdate() {
        Customer customer =
                new Customer(
                        "CUS9990099",
                        "Nguyễn Văn Version",
                        "001099887766",
                        "version.test@example.com",
                        "0901234567",
                        "123 Phố Huế",
                        "Hà Nội",
                        LocalDate.of(1990, 5, 20));
        Customer saved = customerRepository.saveAndFlush(customer);
        Long id = saved.getId();
        assertThat(saved.getVersion()).isZero();

        // Update 1: version 0 -> 1
        UpdateCustomerRequest update1 =
                new UpdateCustomerRequest(
                        "Nguyễn Văn Version Đổi 1",
                        "version1@example.com",
                        "0901234567",
                        "123 Phố Huế",
                        "Hà Nội",
                        LocalDate.of(1990, 5, 20),
                        0L);
        CustomerResponse res1 = customerService.updateCustomer(id, update1);
        assertThat(res1.version()).isEqualTo(1L);

        // Update 2: version 1 -> 2 using the version returned from Update 1
        UpdateCustomerRequest update2 =
                new UpdateCustomerRequest(
                        "Nguyễn Văn Version Đổi 2",
                        "version2@example.com",
                        "0901234567",
                        "123 Phố Huế",
                        "Hà Nội",
                        LocalDate.of(1990, 5, 20),
                        res1.version());
        CustomerResponse res2 = customerService.updateCustomer(id, update2);
        assertThat(res2.version()).isEqualTo(2L);

        // Stale update: using old version 0 should fail with CONCURRENT_MODIFICATION
        UpdateCustomerRequest staleUpdate =
                new UpdateCustomerRequest(
                        "Nguyễn Văn Stale",
                        "stale@example.com",
                        "0901234567",
                        "123 Phố Huế",
                        "Hà Nội",
                        LocalDate.of(1990, 5, 20),
                        0L);
        assertThatThrownBy(() -> customerService.updateCustomer(id, staleUpdate))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    @DisplayName(
            "R1 & R2: Updating account limits returns incremented version and rejects CLOSED"
                    + " account")
    void updateAccountLimits_shouldReturnIncrementedVersionAndRejectClosedAccount() {
        Customer customer =
                new Customer(
                        "CUS9990098",
                        "Trần Hạn Mức",
                        "001099887755",
                        "limits.test@example.com",
                        "0901234568",
                        "456 Kim Mã",
                        "Hà Nội",
                        LocalDate.of(1992, 8, 15));
        Customer savedCustomer = customerRepository.saveAndFlush(customer);

        Account account =
                new Account(
                        "100999999991",
                        savedCustomer.getId(),
                        new BigDecimal("20000000.00"),
                        new BigDecimal("50000000.00"),
                        LocalDate.now());
        Account savedAccount = accountRepository.saveAndFlush(account);
        Long accountId = savedAccount.getId();
        assertThat(savedAccount.getVersion()).isZero();

        // Update limits: version 0 -> 1
        UpdateAccountLimitsRequest updateLimits =
                new UpdateAccountLimitsRequest(
                        new BigDecimal("30000000.00"), new BigDecimal("60000000.00"), 0L);
        var res1 = accountService.updateLimits(accountId, updateLimits);
        assertThat(res1.version()).isEqualTo(1L);

        // Close account
        accountService.closeAccount(accountId);

        // Attempt to update limits on CLOSED account -> must throw INVALID_STATUS_TRANSITION (409)
        UpdateAccountLimitsRequest updateClosed =
                new UpdateAccountLimitsRequest(
                        new BigDecimal("40000000.00"), new BigDecimal("70000000.00"), 1L);
        assertThatThrownBy(() -> accountService.updateLimits(accountId, updateClosed))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }
}
