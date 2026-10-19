package com.example.corebanking.common.config;

import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.event.AccountLimitsUpdatedEvent;
import com.example.corebanking.account.domain.event.AccountStatusChangedEvent;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import com.example.corebanking.transaction.domain.event.TransactionMetadataUpdatedEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event listener that evicts affected cache entries strictly after the originating database
 * transaction commits ({@link TransactionPhase#AFTER_COMMIT}), preventing stale cache repopulation
 * under concurrent reads and avoiding premature eviction when transactions roll back (L2-08, G-05,
 * NFR-3, ADR-009).
 */
@Component
public class CacheEvictionListener {

    private static final Logger log = LoggerFactory.getLogger(CacheEvictionListener.class);

    private final CacheManager cacheManager;
    private final AccountRepository accountRepository;
    private final CacheErrorHandler cacheErrorHandler;

    public CacheEvictionListener(
            CacheManager cacheManager,
            AccountRepository accountRepository,
            CacheErrorHandler cacheErrorHandler) {
        this.cacheManager = cacheManager;
        this.accountRepository = accountRepository;
        this.cacheErrorHandler = cacheErrorHandler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionCompleted(TransactionCompletedEvent event) {
        Set<Long> affectedAccountIds = new LinkedHashSet<>();
        if (event.sourceAccountId() != null) {
            affectedAccountIds.add(event.sourceAccountId());
        }
        if (event.targetAccountId() != null) {
            affectedAccountIds.add(event.targetAccountId());
        }

        Set<Long> affectedCustomerIds = new LinkedHashSet<>();
        for (Long accountId : affectedAccountIds) {
            evictSafely(CacheConfig.RECENT_TRANSACTIONS_CACHE, accountId);
            accountRepository
                    .findById(accountId)
                    .map(Account::getCustomerId)
                    .ifPresent(affectedCustomerIds::add);
        }

        for (Long customerId : affectedCustomerIds) {
            evictSafely(CacheConfig.ACCOUNTS_BY_CUSTOMER_CACHE, customerId);
        }

        log.debug(
                "Evicted caches after transaction commit [txnId={}, accounts={}, customers={}]",
                event.transactionId(),
                affectedAccountIds,
                affectedCustomerIds);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionMetadataUpdated(TransactionMetadataUpdatedEvent event) {
        if (event.sourceAccountId() != null) {
            evictSafely(CacheConfig.RECENT_TRANSACTIONS_CACHE, event.sourceAccountId());
        }
        if (event.targetAccountId() != null
                && !event.targetAccountId().equals(event.sourceAccountId())) {
            evictSafely(CacheConfig.RECENT_TRANSACTIONS_CACHE, event.targetAccountId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountStatusChanged(AccountStatusChangedEvent event) {
        if (event.customerId() != null) {
            evictSafely(CacheConfig.ACCOUNTS_BY_CUSTOMER_CACHE, event.customerId());
        }
        if (event.accountId() != null) {
            evictSafely(CacheConfig.RECENT_TRANSACTIONS_CACHE, event.accountId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountLimitsUpdated(AccountLimitsUpdatedEvent event) {
        if (event.customerId() != null) {
            evictSafely(CacheConfig.ACCOUNTS_BY_CUSTOMER_CACHE, event.customerId());
        }
    }

    private void evictSafely(String cacheName, Object key) {
        Cache cache = null;
        try {
            cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.evict(key);
            }
        } catch (RuntimeException ex) {
            cacheErrorHandler.handleCacheEvictError(ex, cache, key);
        }
    }
}
