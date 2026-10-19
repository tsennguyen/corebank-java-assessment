package com.example.corebanking.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.account.domain.Account;
import com.example.corebanking.account.domain.AccountStatus;
import com.example.corebanking.account.domain.BalanceTier;
import com.example.corebanking.account.domain.event.AccountLimitsUpdatedEvent;
import com.example.corebanking.account.domain.event.AccountStatusChangedEvent;
import com.example.corebanking.account.infrastructure.AccountRepository;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.example.corebanking.transaction.application.TransactionService;
import com.example.corebanking.transaction.domain.TransactionStatus;
import com.example.corebanking.transaction.domain.TransactionType;
import com.example.corebanking.transaction.domain.event.TransactionCompletedEvent;
import com.example.corebanking.transaction.domain.event.TransactionMetadataUpdatedEvent;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;

class ResilientCacheErrorHandlerTest {

    private ResilientCacheErrorHandler errorHandler;
    private Cache mockCache;

    @BeforeEach
    void setUp() {
        errorHandler = new ResilientCacheErrorHandler();
        mockCache = mock(Cache.class);
        when(mockCache.getName()).thenReturn(CacheConfig.ACCOUNTS_BY_CUSTOMER_CACHE);
    }

    @Test
    @DisplayName("handleCacheGetError swallows RedisConnectionFailureException without throwing")
    void handleCacheGetError_whenRedisDown_shouldSwallowException() {
        RedisConnectionFailureException ex =
                new RedisConnectionFailureException("Redis connection refused");

        assertThatCode(() -> errorHandler.handleCacheGetError(ex, mockCache, 42L))
                .doesNotThrowAnyException();
        assertThatCode(() -> errorHandler.handleCacheGetError(ex, null, 42L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "handleCachePutError, handleCacheEvictError, and handleCacheClearError swallow"
                    + " exceptions")
    void handleCacheWriteAndEvictErrors_whenRedisDown_shouldSwallowExceptions() {
        RedisConnectionFailureException ex =
                new RedisConnectionFailureException("Redis command timed out");

        assertThatCode(() -> errorHandler.handleCachePutError(ex, mockCache, 42L, List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> errorHandler.handleCacheEvictError(ex, mockCache, 42L))
                .doesNotThrowAnyException();
        assertThatCode(() -> errorHandler.handleCacheClearError(ex, mockCache))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "CacheConfig creates RedisCacheManager, CaffeineCacheManager, and serializes Java 21"
                    + " records cleanly")
    void cacheConfig_shouldConfigureManagersAndSerializeRecords() {
        CacheProperties props = new CacheProperties();
        props.setMaximumSize(5000L);
        props.setAccountsByCustomerTtl(Duration.ofMinutes(10));
        props.setRecentTransactionsTtl(Duration.ofMinutes(5));
        props.setCustomerTypesTtl(Duration.ofMinutes(60));
        props.setCaffeineAccountsByCustomerTtl(Duration.ofMinutes(2));
        props.setCaffeineRecentTransactionsTtl(Duration.ofMinutes(1));
        props.setCaffeineCustomerTypesTtl(Duration.ofMinutes(60));

        assertThat(props.getMaximumSize()).isEqualTo(5000L);
        assertThat(props.getAccountsByCustomerTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.getRecentTransactionsTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.getCustomerTypesTtl()).isEqualTo(Duration.ofMinutes(60));

        CacheConfig config = new CacheConfig();
        assertThat(config.errorHandler()).isInstanceOf(ResilientCacheErrorHandler.class);

        CacheManager caffeineManager = config.caffeineCacheManager(props);
        assertThat(caffeineManager.getCache(CacheConfig.ACCOUNTS_BY_CUSTOMER_CACHE)).isNotNull();
        assertThat(caffeineManager.getCache(CacheConfig.RECENT_TRANSACTIONS_CACHE)).isNotNull();
        assertThat(caffeineManager.getCache(CacheConfig.CUSTOMER_TYPES_CACHE)).isNotNull();

        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        CacheManager redisManager = config.redisCacheManager(connectionFactory, props);
        assertThat(redisManager.getCache(CacheConfig.ACCOUNTS_BY_CUSTOMER_CACHE)).isNotNull();
        assertThat(redisManager.getCache(CacheConfig.RECENT_TRANSACTIONS_CACHE)).isNotNull();
        assertThat(redisManager.getCache(CacheConfig.CUSTOMER_TYPES_CACHE)).isNotNull();

        ObjectMapper mapper = CacheConfig.createRedisObjectMapper();
        JavaType accountListType =
                mapper.getTypeFactory().constructCollectionType(List.class, AccountResponse.class);
        Jackson2JsonRedisSerializer<List<AccountResponse>> accountSerializer =
                new Jackson2JsonRedisSerializer<>(mapper, accountListType);

        AccountResponse sampleAccount =
                new AccountResponse(
                        10L,
                        "100000000016",
                        5L,
                        "VND",
                        new BigDecimal("25000000.00"),
                        new BigDecimal("50000000.00"),
                        new BigDecimal("100000000.00"),
                        LocalDate.of(2026, 10, 15),
                        AccountStatus.ACTIVE,
                        BalanceTier.MEDIUM,
                        null,
                        Instant.parse("2026-10-15T10:00:00Z"),
                        Instant.parse("2026-10-15T10:00:00Z"),
                        0L);

        byte[] accountBytes = accountSerializer.serialize(List.of(sampleAccount));
        List<AccountResponse> deserializedAccounts = accountSerializer.deserialize(accountBytes);
        assertThat(deserializedAccounts).containsExactly(sampleAccount);

        JavaType txPageType =
                mapper.getTypeFactory()
                        .constructParametricType(PageResponse.class, TransactionResponse.class);
        Jackson2JsonRedisSerializer<PageResponse<TransactionResponse>> txPageSerializer =
                new Jackson2JsonRedisSerializer<>(mapper, txPageType);

        TransactionResponse sampleTx =
                new TransactionResponse(
                        99L,
                        "TXN-20261015-001",
                        TransactionType.TRANSFER,
                        TransactionStatus.COMPLETED,
                        new BigDecimal("1000000.00"),
                        new BigDecimal("2000.00"),
                        10L,
                        20L,
                        new BigDecimal("23998000.00"),
                        new BigDecimal("11000000.00"),
                        new BigDecimal("-2000.00"),
                        "Ha Noi",
                        "Chuyen khoan",
                        Instant.parse("2026-10-15T11:00:00Z"),
                        null,
                        null);
        PageResponse<TransactionResponse> samplePage =
                new PageResponse<>(List.of(sampleTx), 0, 20, 1L, 1);

        byte[] pageBytes = txPageSerializer.serialize(samplePage);
        PageResponse<TransactionResponse> deserializedPage =
                txPageSerializer.deserialize(pageBytes);
        assertThat(deserializedPage).isEqualTo(samplePage);
    }

    @Test
    @DisplayName(
            "CacheEvictionListener evicts both source and target accounts and customers on transfer"
                    + " and handles Redis errors gracefully")
    void cacheEvictionListener_shouldEvictAffectedKeysAndDelegateErrors() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache accountsCache = mock(Cache.class);
        Cache txCache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.ACCOUNTS_BY_CUSTOMER_CACHE))
                .thenReturn(accountsCache);
        when(cacheManager.getCache(CacheConfig.RECENT_TRANSACTIONS_CACHE)).thenReturn(txCache);

        AccountRepository accountRepository = mock(AccountRepository.class);
        Account acc1 = mock(Account.class);
        when(acc1.getCustomerId()).thenReturn(101L);
        Account acc2 = mock(Account.class);
        when(acc2.getCustomerId()).thenReturn(202L);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(acc1));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(acc2));

        CacheEvictionListener listener =
                new CacheEvictionListener(cacheManager, accountRepository, errorHandler);

        TransactionCompletedEvent transferEvent =
                new TransactionCompletedEvent(
                        500L,
                        "TXN-500",
                        TransactionType.TRANSFER,
                        new BigDecimal("500000.00"),
                        new BigDecimal("2000.00"),
                        1L,
                        2L,
                        Instant.now());

        listener.onTransactionCompleted(transferEvent);

        verify(txCache).evict(1L);
        verify(txCache).evict(2L);
        verify(accountsCache).evict(101L);
        verify(accountsCache).evict(202L);

        listener.onTransactionMetadataUpdated(
                new TransactionMetadataUpdatedEvent(500L, 1L, 2L, Instant.now()));

        listener.onAccountStatusChanged(
                new AccountStatusChangedEvent(
                        1L,
                        101L,
                        AccountStatus.ACTIVE,
                        AccountStatus.LOCKED,
                        "Lock",
                        "admin",
                        Instant.now()));

        listener.onAccountLimitsUpdated(new AccountLimitsUpdatedEvent(1L, 101L, Instant.now()));

        doThrow(new RedisConnectionFailureException("Redis down")).when(accountsCache).evict(999L);
        assertThatCode(
                        () ->
                                listener.onAccountLimitsUpdated(
                                        new AccountLimitsUpdatedEvent(9L, 999L, Instant.now())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "TransactionService.isDefaultRecentTransactionsPage matches only page 0 size 20"
                    + " default sort")
    void isDefaultRecentTransactionsPage_shouldMatchOnlyDefaultFirstPage() {
        assertThat(TransactionService.isDefaultRecentTransactionsPage(null)).isFalse();
        assertThat(TransactionService.isDefaultRecentTransactionsPage(Pageable.unpaged()))
                .isFalse();
        assertThat(TransactionService.isDefaultRecentTransactionsPage(PageRequest.of(1, 20)))
                .isFalse();
        assertThat(TransactionService.isDefaultRecentTransactionsPage(PageRequest.of(0, 10)))
                .isFalse();
        assertThat(TransactionService.isDefaultRecentTransactionsPage(PageRequest.of(0, 20)))
                .isTrue();
        assertThat(
                        TransactionService.isDefaultRecentTransactionsPage(
                                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt")))))
                .isTrue();
        assertThat(
                        TransactionService.isDefaultRecentTransactionsPage(
                                PageRequest.of(
                                        0,
                                        20,
                                        Sort.by(
                                                Sort.Order.desc("createdAt"),
                                                Sort.Order.desc("id")))))
                .isTrue();
        assertThat(
                        TransactionService.isDefaultRecentTransactionsPage(
                                PageRequest.of(0, 20, Sort.by(Sort.Order.asc("createdAt")))))
                .isFalse();
        assertThat(
                        TransactionService.isDefaultRecentTransactionsPage(
                                PageRequest.of(0, 20, Sort.by(Sort.Order.desc("amount")))))
                .isFalse();
    }
}
