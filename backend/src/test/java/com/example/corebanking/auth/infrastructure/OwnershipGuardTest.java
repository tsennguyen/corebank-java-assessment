package com.example.corebanking.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.transaction.domain.Transaction;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class OwnershipGuardTest {

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;

    private OwnershipGuard ownershipGuard;

    @BeforeEach
    void setUp() {
        ownershipGuard = new OwnershipGuard(accountRepository, transactionRepository);
    }

    private Authentication createJwtAuth(String username, String role, Object customerIdClaim) {
        Jwt.Builder builder =
                Jwt.withTokenValue("mock-jwt-token")
                        .header("alg", "HS256")
                        .subject("1")
                        .claim("username", username)
                        .claim("role", role)
                        .issuedAt(Instant.parse("2026-10-14T10:00:00Z"))
                        .expiresAt(Instant.parse("2026-10-14T10:15:00Z"));
        if (customerIdClaim != null) {
            builder.claim("customerId", customerIdClaim);
        }
        Jwt jwt = builder.build();
        return new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)), username);
    }

    @Test
    @DisplayName("canAccessAccount returns true immediately for ADMIN without querying database")
    void canAccessAccount_whenAdmin_shouldReturnTrue() {
        Authentication adminAuth = createJwtAuth("admin", "ADMIN", null);

        boolean result = ownershipGuard.canAccessAccount(adminAuth, 100L);

        assertThat(result).isTrue();
        verifyNoInteractions(accountRepository, transactionRepository);
    }

    @Test
    @DisplayName("canAccessAccount returns true when CUSTOMER owns the requested account")
    void canAccessAccount_whenCustomerOwnsAccount_shouldReturnTrue() {
        Authentication customerAuth = createJwtAuth("cust.an", "CUSTOMER", 10L);
        when(accountRepository.existsByIdAndCustomerId(100L, 10L)).thenReturn(true);

        boolean result = ownershipGuard.canAccessAccount(customerAuth, 100L);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName(
            "canAccessAccount returns false when CUSTOMER does not own the requested account"
                    + " (IDOR protection)")
    void canAccessAccount_whenCustomerDoesNotOwnAccount_shouldReturnFalse() {
        Authentication customerAuth = createJwtAuth("cust.an", "CUSTOMER", 10L);
        when(accountRepository.existsByIdAndCustomerId(200L, 10L)).thenReturn(false);

        boolean result = ownershipGuard.canAccessAccount(customerAuth, 200L);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName(
            "canAccessAccount returns false when authentication is null, unauthenticated, or"
                    + " missing customerId")
    void canAccessAccount_whenInvalidAuthenticationOrNullAccountId_shouldReturnFalse() {
        assertThat(ownershipGuard.canAccessAccount(null, 100L)).isFalse();

        Authentication unauthenticated = new UsernamePasswordAuthenticationToken("cust.an", "pass");
        assertThat(ownershipGuard.canAccessAccount(unauthenticated, 100L)).isFalse();

        Authentication customerAuth = createJwtAuth("cust.an", "CUSTOMER", 10L);
        assertThat(ownershipGuard.canAccessAccount(customerAuth, null)).isFalse();

        Authentication customerWithoutId = createJwtAuth("cust.an", "CUSTOMER", null);
        assertThat(ownershipGuard.canAccessAccount(customerWithoutId, 100L)).isFalse();
    }

    @Test
    @DisplayName(
            "canAccessTransaction returns true when CUSTOMER owns either source or target account"
                    + " of transfer")
    void canAccessTransaction_whenCustomerOwnsSourceOrTargetAccount_shouldReturnTrue() {
        Authentication customerAAuth = createJwtAuth("cust.a", "CUSTOMER", 10L);
        Authentication customerBAuth = createJwtAuth("cust.b", "CUSTOMER", 20L);
        Authentication customerCAuth = createJwtAuth("cust.c", "CUSTOMER", 30L);

        Transaction transferFromBToA =
                Transaction.createTransfer(
                        "TXN-20261014-0001",
                        200L,
                        100L,
                        new BigDecimal("500000.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("1500000.00"),
                        new BigDecimal("2500000.00"),
                        "Hà Nội",
                        "Chuyển khoản B sang A");

        when(accountRepository.findIdsByCustomerId(10L)).thenReturn(Set.of(100L, 101L));
        when(accountRepository.findIdsByCustomerId(20L)).thenReturn(Set.of(200L));
        when(accountRepository.findIdsByCustomerId(30L)).thenReturn(Set.of(300L));
        when(transactionRepository.findById(555L)).thenReturn(Optional.of(transferFromBToA));

        assertThat(ownershipGuard.canAccessTransaction(customerAAuth, 555L)).isTrue();
        assertThat(ownershipGuard.canAccessTransaction(customerBAuth, 555L)).isTrue();
        assertThat(ownershipGuard.canAccessTransaction(customerCAuth, 555L)).isFalse();
    }

    @Test
    @DisplayName(
            "canAccessTransaction handles ADMIN, empty account set, and non-existent transaction")
    void canAccessTransaction_edgeCases_shouldBehaveDeterministically() {
        Authentication adminAuth = createJwtAuth("admin", "ADMIN", null);
        assertThat(ownershipGuard.canAccessTransaction(adminAuth, 999L)).isTrue();

        Authentication customerNoAccounts = createJwtAuth("cust.empty", "CUSTOMER", 40L);
        when(accountRepository.findIdsByCustomerId(40L)).thenReturn(Set.of());
        assertThat(ownershipGuard.canAccessTransaction(customerNoAccounts, 555L)).isFalse();

        Authentication customerA = createJwtAuth("cust.a", "CUSTOMER", 10L);
        when(accountRepository.findIdsByCustomerId(10L)).thenReturn(Set.of(100L));
        when(transactionRepository.findById(999L)).thenReturn(Optional.empty());
        assertThat(ownershipGuard.canAccessTransaction(customerA, 999L)).isFalse();
    }

    @Test
    @DisplayName(
            "extractCustomerId and requireCustomerId support numeric/string claims and reject"
                    + " missing claim")
    void extractAndRequireCustomerId_shouldParseClaimsAndRejectMissing() {
        Authentication stringClaimAuth = createJwtAuth("cust.str", "CUSTOMER", "42");
        assertThat(ownershipGuard.requireCustomerId(stringClaimAuth)).isEqualTo(42L);

        Authentication invalidStringClaimAuth = createJwtAuth("cust.bad", "CUSTOMER", "not-a-long");
        assertThat(ownershipGuard.extractCustomerId(invalidStringClaimAuth)).isNull();

        Authentication adminAuth = createJwtAuth("admin", "ADMIN", null);
        assertThatThrownBy(() -> ownershipGuard.requireCustomerId(adminAuth))
                .isInstanceOf(AccessDeniedException.class);
    }
}
