package com.example.corebanking.auth.infrastructure;

import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.transaction.infrastructure.TransactionRepository;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Security SpEL ownership evaluator ({@code @ownership}) preventing Broken Object Level
 * Authorization (IDOR — OWASP API1) across Account and Transaction endpoints (L2-01, FR-14).
 */
@Component("ownership")
@Transactional(readOnly = true)
public class OwnershipGuard {

    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String CLAIM_CUSTOMER_ID = "customerId";

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public OwnershipGuard(
            AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Checks whether the authenticated principal is allowed to access the specified account.
     *
     * @param authentication current Spring Security authentication context
     * @param accountId target account database ID
     * @return {@code true} if caller is ADMIN or CUSTOMER who owns {@code accountId}
     */
    public boolean canAccessAccount(Authentication authentication, Long accountId) {
        if (authentication == null || !authentication.isAuthenticated() || accountId == null) {
            return false;
        }
        if (hasAdminRole(authentication)) {
            return true;
        }
        Long customerId = extractCustomerId(authentication);
        if (customerId == null) {
            return false;
        }
        return accountRepository.existsByIdAndCustomerId(accountId, customerId);
    }

    /**
     * Checks whether the authenticated principal is allowed to access the specified transaction. A
     * customer may access a transaction if either {@code sourceAccountId} or {@code
     * targetAccountId} belongs to one of the customer's accounts.
     *
     * @param authentication current Spring Security authentication context
     * @param transactionId target transaction database ID
     * @return {@code true} if caller is ADMIN or CUSTOMER who owns source or target account
     */
    public boolean canAccessTransaction(Authentication authentication, Long transactionId) {
        if (authentication == null || !authentication.isAuthenticated() || transactionId == null) {
            return false;
        }
        if (hasAdminRole(authentication)) {
            return true;
        }
        Long customerId = extractCustomerId(authentication);
        if (customerId == null) {
            return false;
        }
        Set<Long> ownedAccountIds = accountRepository.findIdsByCustomerId(customerId);
        if (ownedAccountIds == null || ownedAccountIds.isEmpty()) {
            return false;
        }
        return transactionRepository
                .findById(transactionId)
                .map(
                        tx ->
                                (tx.getSourceAccountId() != null
                                                && ownedAccountIds.contains(
                                                        tx.getSourceAccountId()))
                                        || (tx.getTargetAccountId() != null
                                                && ownedAccountIds.contains(
                                                        tx.getTargetAccountId())))
                .orElse(false);
    }

    /**
     * Extracts the {@code customerId} claim from a verified JWT principal, or returns {@code null}
     * if absent/invalid.
     */
    public Long extractCustomerId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            Object claim = jwt.getClaim(CLAIM_CUSTOMER_ID);
            if (claim instanceof Number number) {
                return number.longValue();
            }
            if (claim instanceof String str && !str.isBlank()) {
                try {
                    return Long.parseLong(str.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Extracts the {@code customerId} claim or throws {@link AccessDeniedException} (HTTP 403
     * {@code ACCESS_DENIED}) if the token does not represent a customer.
     */
    public Long requireCustomerId(Authentication authentication) {
        Long customerId = extractCustomerId(authentication);
        if (customerId == null) {
            throw new AccessDeniedException(
                    "Self-service endpoint requires a valid customerId claim");
        }
        return customerId;
    }

    private boolean hasAdminRole(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ROLE_ADMIN.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
