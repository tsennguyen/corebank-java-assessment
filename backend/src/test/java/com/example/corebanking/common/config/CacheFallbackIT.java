package com.example.corebanking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.api.dto.ChangeAccountStatusRequest;
import com.example.corebanking.account.api.dto.CreateAccountRequest;
import com.example.corebanking.account.api.dto.UpdateAccountLimitsRequest;
import com.example.corebanking.account.application.AccountService;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.customer.api.dto.CreateCustomerRequest;
import com.example.corebanking.customer.api.dto.CustomerResponse;
import com.example.corebanking.customer.application.CustomerService;
import com.example.corebanking.support.IntegrationTestBase;
import com.example.corebanking.transaction.api.dto.DepositRequest;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.api.dto.TransferRequest;
import com.example.corebanking.transaction.api.dto.WithdrawalRequest;
import com.example.corebanking.transaction.application.TransactionService;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Integration tests verifying query cache hit/miss behavior, {@code AFTER_COMMIT} cache eviction on
 * financial and lifecycle mutations, and rollback non-eviction guarantees on PostgreSQL 17 (Story
 * 3.7: L2-08, G-05, NFR-3, ADR-009).
 */
class CacheFallbackIT extends IntegrationTestBase {

    @Autowired private CustomerService customerService;
    @Autowired private AccountService accountService;
    @Autowired private TransactionService transactionService;
    @Autowired private CacheManager cacheManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Statistics hibernateStats;

    @BeforeEach
    void setUpStats() {
        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        hibernateStats = sessionFactory.getStatistics();
        hibernateStats.setStatisticsEnabled(true);
        hibernateStats.clear();
    }

    @Test
    @DisplayName(
            "getAccountsByCustomerId hits cache on second call (0 SQL queries) and evicts"
                    + " AFTER_COMMIT on deposit, limit update, and status change (AC 2, AC 3)")
    void getAccountsByCustomerId_shouldHitCacheAndEvictAfterCommitOnMutations() {
        CustomerResponse customer = createUniqueCustomer("CacheCustA");
        AccountResponse account =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customer.id(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("100000000.00")));

        // 1st read after creation -> cache miss (1 SQL query)
        hibernateStats.clear();
        List<AccountResponse> firstRead = accountService.getAccountsByCustomerId(customer.id());
        assertThat(firstRead).hasSize(1);
        assertThat(firstRead.get(0).balance()).isEqualByComparingTo("0.00");
        assertThat(hibernateStats.getPrepareStatementCount()).isEqualTo(1L);

        // 2nd read -> cache hit (0 SQL queries)
        hibernateStats.clear();
        List<AccountResponse> secondRead = accountService.getAccountsByCustomerId(customer.id());
        assertThat(secondRead).hasSize(1);
        assertThat(hibernateStats.getPrepareStatementCount()).isZero();

        // Deposit commits -> AFTER_COMMIT evicts accountsByCustomer cache
        transactionService.deposit(
                new DepositRequest(account.id(), new BigDecimal("15000000.00"), "HCM", "Nap tien"));

        // Next read -> cache miss, returns fresh balance 15,000,000.00
        hibernateStats.clear();
        List<AccountResponse> afterDeposit = accountService.getAccountsByCustomerId(customer.id());
        assertThat(afterDeposit.get(0).balance()).isEqualByComparingTo("15000000.00");
        assertThat(hibernateStats.getPrepareStatementCount()).isEqualTo(1L);

        // Update limits commits -> AFTER_COMMIT evicts accountsByCustomer cache
        AccountResponse current = accountService.getAccountById(account.id());
        accountService.updateLimits(
                account.id(),
                new UpdateAccountLimitsRequest(
                        new BigDecimal("60000000.00"),
                        new BigDecimal("120000000.00"),
                        current.version()));

        List<AccountResponse> afterLimitUpdate =
                accountService.getAccountsByCustomerId(customer.id());
        assertThat(afterLimitUpdate.get(0).transactionLimit()).isEqualByComparingTo("60000000.00");

        // Status change commits -> AFTER_COMMIT evicts accountsByCustomer cache
        accountService.changeStatus(
                account.id(),
                new ChangeAccountStatusRequest(AccountStatus.LOCKED, "Tam khoa kiem tra"));

        List<AccountResponse> afterLock = accountService.getAccountsByCustomerId(customer.id());
        assertThat(afterLock.get(0).status()).isEqualTo(AccountStatus.LOCKED);
    }

    @Test
    @DisplayName(
            "getAccountTransactions caches default first page (0 SQL on 2nd call), evicts both"
                    + " source and target on transfer/reversal, and preserves cache on rollback"
                    + " (AC 2, AC 3)")
    void getAccountTransactions_shouldCacheFirstPageAndEvictOnlyOnCommit() {
        CustomerResponse customerA = createUniqueCustomer("CacheTxA");
        CustomerResponse customerB = createUniqueCustomer("CacheTxB");

        AccountResponse accountA =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customerA.id(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("100000000.00")));
        AccountResponse accountB =
                accountService.createAccount(
                        new CreateAccountRequest(
                                customerB.id(),
                                new BigDecimal("50000000.00"),
                                new BigDecimal("100000000.00")));

        transactionService.deposit(
                new DepositRequest(accountA.id(), new BigDecimal("5000000.00"), "HN", "Seed A"));

        PageRequest defaultPage = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        // 1st call on A & B -> cache miss
        hibernateStats.clear();
        PageResponse<TransactionResponse> firstPageA =
                transactionService.getAccountTransactions(accountA.id(), defaultPage);
        assertThat(firstPageA.content()).hasSize(1);
        assertThat(hibernateStats.getPrepareStatementCount()).isLessThanOrEqualTo(2L);

        PageResponse<TransactionResponse> firstPageB =
                transactionService.getAccountTransactions(accountB.id(), defaultPage);
        assertThat(firstPageB.content()).isEmpty();

        // 2nd call on A -> cache hit (0 SQL queries)
        hibernateStats.clear();
        PageResponse<TransactionResponse> cachedPageA =
                transactionService.getAccountTransactions(accountA.id(), defaultPage);
        assertThat(cachedPageA.content()).hasSize(1);
        assertThat(hibernateStats.getPrepareStatementCount()).isZero();

        // Non-default page (page=1) bypasses cache and queries DB directly
        hibernateStats.clear();
        PageResponse<TransactionResponse> secondPageNumber =
                transactionService.getAccountTransactions(
                        accountA.id(),
                        PageRequest.of(1, 20, Sort.by(Sort.Direction.DESC, "createdAt")));
        assertThat(secondPageNumber.content()).isEmpty();
        assertThat(hibernateStats.getPrepareStatementCount()).isGreaterThan(0L);

        // Failed withdrawal (insufficient balance) rolls back -> cache for A must NOT be evicted
        assertThatThrownBy(
                        () ->
                                transactionService.withdraw(
                                        new WithdrawalRequest(
                                                accountA.id(),
                                                new BigDecimal("99000000.00"),
                                                "HN",
                                                "Rut qua so du")))
                .isInstanceOf(BusinessRuleException.class);

        Cache recentTxCache = cacheManager.getCache(CacheConfig.RECENT_TRANSACTIONS_CACHE);
        assertThat(recentTxCache).isNotNull();
        assertThat(recentTxCache.get(accountA.id())).isNotNull();

        // Transfer from A -> B commits -> AFTER_COMMIT evicts both A and B from recentTransactions
        TransactionResponse transferTx =
                transactionService.transfer(
                        new TransferRequest(
                                accountA.id(),
                                accountB.id(),
                                new BigDecimal("1000000.00"),
                                "HN",
                                "Chuyen A sang B"));

        assertThat(recentTxCache.get(accountA.id())).isNull();
        assertThat(recentTxCache.get(accountB.id())).isNull();

        // Subsequent reads see the new transfer on both A and B
        assertThat(transactionService.getAccountTransactions(accountA.id(), defaultPage).content())
                .hasSize(2);
        assertThat(transactionService.getAccountTransactions(accountB.id(), defaultPage).content())
                .hasSize(1);

        // Reversal of transfer commits -> AFTER_COMMIT evicts both A and B again
        transactionService.reverseTransaction(transferTx.id());
        assertThat(transactionService.getAccountTransactions(accountA.id(), defaultPage).content())
                .hasSize(3);
        assertThat(transactionService.getAccountTransactions(accountB.id(), defaultPage).content())
                .hasSize(2);
    }

    private CustomerResponse createUniqueCustomer(String prefix) {
        String suffix = UUID.randomUUID().toString().replaceAll("[^0-9]", "");
        while (suffix.length() < 12) {
            suffix = suffix + "123456789012";
        }
        String nationalId = suffix.substring(0, 12);
        String phone = "09" + suffix.substring(0, 8);
        String customerCode = "CUS" + suffix.substring(0, 7);
        return customerService.createCustomer(
                new CreateCustomerRequest(
                        customerCode,
                        prefix + " User",
                        nationalId,
                        phone,
                        prefix.toLowerCase() + "." + nationalId.substring(0, 6) + "@example.com",
                        "TP. Ho Chi Minh",
                        LocalDate.of(1995, 5, 15)));
    }
}
