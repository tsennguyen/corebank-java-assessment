package com.example.corebanking.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import com.example.corebanking.common.domain.Money;
import com.example.corebanking.customer.api.dto.CustomerTypeResponse;
import com.example.corebanking.customer.application.CustomerTypeService;
import com.example.corebanking.customer.domain.Customer;
import com.example.corebanking.customer.domain.CustomerType;
import com.example.corebanking.customer.infrastructure.CustomerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CustomerTypeLimitProvider & CustomerType Domain Unit Tests")
class CustomerTypeLimitProviderTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private CustomerTypeService customerTypeService;

    private CustomerTypeLimitProvider limitProvider;
    private LimitPolicy limitPolicy;

    private static final CustomerTypeResponse INDIVIDUAL_TYPE =
            new CustomerTypeResponse(
                    1L,
                    "INDIVIDUAL",
                    "Cá nhân",
                    new BigDecimal("100000000.00"),
                    new BigDecimal("500000000.00"),
                    Instant.parse("2026-10-09T01:00:00Z"),
                    0L);

    private static final CustomerTypeResponse BUSINESS_TYPE =
            new CustomerTypeResponse(
                    2L,
                    "BUSINESS",
                    "Doanh nghiệp",
                    new BigDecimal("2000000000.00"),
                    new BigDecimal("10000000000.00"),
                    Instant.parse("2026-10-09T01:00:00Z"),
                    0L);

    @BeforeEach
    void setUp() {
        limitProvider = new CustomerTypeLimitProvider(customerRepository, customerTypeService);
        limitPolicy = new LimitPolicy(limitProvider);
    }

    @Test
    @DisplayName(
            "INDIVIDUAL customer with account transactionLimit=300M is capped at 100M by"
                    + " customerType")
    void resolveLimits_individualCustomerWithHigherAccountLimits_shouldCapAtCustomerTypeLimits() {
        Customer individual =
                new Customer(
                        "CUS0000001",
                        "Nguyen Van An",
                        "000123456789",
                        "an@example.com",
                        "0900000001",
                        "Ha Noi",
                        "Ha Noi",
                        LocalDate.of(1990, 1, 1),
                        1L,
                        "INDIVIDUAL");
        Account account =
                new Account(
                        "100000000001",
                        10L,
                        new BigDecimal("300000000.00"),
                        new BigDecimal("800000000.00"),
                        LocalDate.of(2026, 10, 10));

        when(customerRepository.findById(10L)).thenReturn(Optional.of(individual));
        when(customerTypeService.getAllCustomerTypes())
                .thenReturn(List.of(INDIVIDUAL_TYPE, BUSINESS_TYPE));

        AccountLimits effective = limitProvider.getLimits(account);
        assertThat(effective.transactionLimit()).isEqualByComparingTo("100000000.00");
        assertThat(effective.dailyLimit()).isEqualByComparingTo("500000000.00");

        assertThatThrownBy(
                        () ->
                                limitPolicy.validateLimits(
                                        account, Money.of("150000000.00"), Money.of("0.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex -> {
                            BusinessRuleException bre = (BusinessRuleException) ex;
                            assertThat(bre.getErrorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
                            assertThat(bre.getMessage()).contains("100000000.00");
                        });

        // Also verify daily limit is capped at customerType.maxDailyLimit (500M) even when account
        // dailyLimit is 800M
        assertThatThrownBy(
                        () ->
                                limitPolicy.validateLimits(
                                        account, Money.of("80000000.00"), Money.of("450000000.00")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex -> {
                            BusinessRuleException bre = (BusinessRuleException) ex;
                            assertThat(bre.getErrorCode())
                                    .isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED);
                            assertThat(bre.getMessage()).contains("500000000.00");
                        });
    }

    @Test
    @DisplayName(
            "BUSINESS customer with account transactionLimit=300M allows 150M transfer"
                    + " (min(300M, 2B) = 300M)")
    void resolveLimits_businessCustomerWithAccountLimitsBelowTypeCeiling_shouldUseAccountLimits() {
        Customer business =
                new Customer(
                        "CUS0000002",
                        "Cong ty Mau",
                        "000123456780",
                        "corp@example.com",
                        "0900000002",
                        "Ho Chi Minh",
                        "Ho Chi Minh",
                        LocalDate.of(1985, 1, 1),
                        2L,
                        "BUSINESS");
        Account account =
                new Account(
                        "100000000002",
                        20L,
                        new BigDecimal("300000000.00"),
                        new BigDecimal("800000000.00"),
                        LocalDate.of(2026, 10, 10));

        when(customerRepository.findById(20L)).thenReturn(Optional.of(business));
        when(customerTypeService.getAllCustomerTypes())
                .thenReturn(List.of(INDIVIDUAL_TYPE, BUSINESS_TYPE));

        AccountLimits effective = limitProvider.getLimits(account);
        assertThat(effective.transactionLimit()).isEqualByComparingTo("300000000.00");
        assertThat(effective.dailyLimit()).isEqualByComparingTo("800000000.00");

        assertThatCode(
                        () ->
                                limitPolicy.validateLimits(
                                        account, Money.of("150000000.00"), Money.of("0.00")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Falls back to account limits when customer or customerType is not found")
    void resolveLimits_whenCustomerNotFound_shouldFallbackToAccountLimits() {
        Account account =
                new Account(
                        "100000000003",
                        999L,
                        new BigDecimal("50000000.00"),
                        new BigDecimal("200000000.00"),
                        LocalDate.of(2026, 10, 10));

        when(customerRepository.findById(999L)).thenReturn(Optional.empty());

        AccountLimits effective = limitProvider.getLimits(account);
        assertThat(effective.transactionLimit()).isEqualByComparingTo("50000000.00");
        assertThat(effective.dailyLimit()).isEqualByComparingTo("200000000.00");
    }

    @Test
    @DisplayName(
            "CustomerType domain enforces maxDailyLimit >= maxTransactionLimit > 0 and allows"
                    + " equality boundary")
    void customerTypeDomain_shouldEnforceLimitInvariants() {
        CustomerType type =
                new CustomerType(
                        1L,
                        "individual",
                        "Cá nhân",
                        new BigDecimal("100000000.00"),
                        new BigDecimal("100000000.00"));
        assertThat(type.getCode()).isEqualTo("INDIVIDUAL");
        assertThat(type.getMaxTransactionLimit()).isEqualByComparingTo("100000000.00");
        assertThat(type.getMaxDailyLimit()).isEqualByComparingTo("100000000.00");

        assertThatThrownBy(
                        () ->
                                type.updateLimits(
                                        new BigDecimal("200000000.00"),
                                        new BigDecimal("199999999.99")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                assertThat(((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> type.updateLimits(BigDecimal.ZERO, new BigDecimal("100.00")))
                .isInstanceOf(BusinessRuleException.class);
    }
}
