package com.example.corebanking.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountCustomerDeletionGuardTest {

    @Mock private AccountRepository accountRepository;

    private AccountCustomerDeletionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new AccountCustomerDeletionGuard(accountRepository);
    }

    @Test
    @DisplayName("validateCanDeleteCustomer succeeds when customer has no active accounts")
    void validateCanDeleteCustomer_shouldSucceedWhenNoActiveAccounts() {
        when(accountRepository.existsByCustomerIdAndStatusNot(1L, AccountStatus.CLOSED))
                .thenReturn(false);

        assertThatCode(() -> guard.validateCanDeleteCustomer(1L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "validateCanDeleteCustomer throws CUSTOMER_HAS_ACTIVE_ACCOUNTS when non-closed accounts"
                    + " exist")
    void validateCanDeleteCustomer_shouldThrowWhenNonClosedAccountsExist() {
        when(accountRepository.existsByCustomerIdAndStatusNot(1L, AccountStatus.CLOSED))
                .thenReturn(true);

        assertThatThrownBy(() -> guard.validateCanDeleteCustomer(1L))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ((BusinessRuleException) ex).getErrorCode())
                                        .isEqualTo(ErrorCode.CUSTOMER_HAS_ACTIVE_ACCOUNTS))
                .hasMessageContaining("Customer still has non-closed accounts");
    }
}
