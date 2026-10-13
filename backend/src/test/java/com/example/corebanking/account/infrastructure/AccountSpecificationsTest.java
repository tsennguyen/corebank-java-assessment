package com.example.corebanking.account.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

class AccountSpecificationsTest {

    @SuppressWarnings("unchecked")
    private final Root<Account> root = mock(Root.class);

    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);

    @Test
    @DisplayName("withAccountNumber produces equal predicate for exact matching (R7)")
    void withAccountNumber_shouldProduceEqualPredicate() {
        @SuppressWarnings("unchecked")
        Path<Object> path = mock(Path.class);
        Predicate expectedPredicate = mock(Predicate.class);

        when(root.get("accountNumber")).thenReturn(path);
        when(cb.equal(path, "100000000016")).thenReturn(expectedPredicate);

        Specification<Account> spec = AccountSpecifications.withAccountNumber(" 100000000016 ");
        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isSameAs(expectedPredicate);
        verify(cb).equal(path, "100000000016");
    }

    @Test
    @DisplayName("withAccountNumber returns conjunction when null or blank")
    void withAccountNumber_shouldReturnConjunctionWhenBlank() {
        Predicate conjunction = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);

        Specification<Account> specNull = AccountSpecifications.withAccountNumber(null);
        assertThat(specNull.toPredicate(root, query, cb)).isSameAs(conjunction);

        Specification<Account> specBlank = AccountSpecifications.withAccountNumber("   ");
        assertThat(specBlank.toPredicate(root, query, cb)).isSameAs(conjunction);

        verifyNoInteractions(root);
    }

    @Test
    @DisplayName("withCustomerId produces equal predicate when present")
    void withCustomerId_shouldProduceEqualPredicate() {
        @SuppressWarnings("unchecked")
        Path<Object> path = mock(Path.class);
        Predicate expected = mock(Predicate.class);

        when(root.get("customerId")).thenReturn(path);
        when(cb.equal(path, 1L)).thenReturn(expected);

        Specification<Account> spec = AccountSpecifications.withCustomerId(1L);
        assertThat(spec.toPredicate(root, query, cb)).isSameAs(expected);
    }

    @Test
    @DisplayName("withStatus produces equal predicate when present")
    void withStatus_shouldProduceEqualPredicate() {
        @SuppressWarnings("unchecked")
        Path<Object> path = mock(Path.class);
        Predicate expected = mock(Predicate.class);

        when(root.get("status")).thenReturn(path);
        when(cb.equal(path, AccountStatus.ACTIVE)).thenReturn(expected);

        Specification<Account> spec = AccountSpecifications.withStatus(AccountStatus.ACTIVE);
        assertThat(spec.toPredicate(root, query, cb)).isSameAs(expected);
    }
}
