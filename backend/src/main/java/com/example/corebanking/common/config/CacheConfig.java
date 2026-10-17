package com.example.corebanking.common.config;

import com.example.corebanking.account.api.dto.AccountResponse;
import com.example.corebanking.common.api.PageResponse;
import com.example.corebanking.transaction.api.dto.TransactionResponse;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Dual-mode cache configuration providing Redis cache-aside when {@code app.redis.enabled=true} and
 * an in-memory Caffeine fallback when {@code app.redis.enabled=false}, protected by {@link
 * ResilientCacheErrorHandler} for runtime fault tolerance (L2-08, G-05, NFR-3, ADR-009).
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheProperties.class)
public class CacheConfig implements CachingConfigurer {

    public static final String ACCOUNTS_BY_CUSTOMER_CACHE = "accountsByCustomer";
    public static final String RECENT_TRANSACTIONS_CACHE = "recentTransactions";
    public static final String CUSTOMER_TYPES_CACHE = "customerTypes";
    private static final String KEY_PREFIX = "corebank::";

    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new ResilientCacheErrorHandler();
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "true")
    public CacheManager redisCacheManager(
            RedisConnectionFactory connectionFactory, CacheProperties properties) {
        ObjectMapper objectMapper = createRedisObjectMapper();

        JavaType accountListType =
                objectMapper
                        .getTypeFactory()
                        .constructCollectionType(List.class, AccountResponse.class);
        Jackson2JsonRedisSerializer<List<AccountResponse>> accountListSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, accountListType);

        JavaType txPageType =
                objectMapper
                        .getTypeFactory()
                        .constructParametricType(PageResponse.class, TransactionResponse.class);
        Jackson2JsonRedisSerializer<PageResponse<TransactionResponse>> txPageSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, txPageType);

        GenericJackson2JsonRedisSerializer genericSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration defaultConfig =
                RedisCacheConfiguration.defaultCacheConfig()
                        .disableCachingNullValues()
                        .prefixCacheNameWith(KEY_PREFIX)
                        .entryTtl(properties.getCustomerTypesTtl())
                        .serializeKeysWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(
                                        new StringRedisSerializer()))
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(
                                        genericSerializer));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put(
                ACCOUNTS_BY_CUSTOMER_CACHE,
                defaultConfig
                        .entryTtl(properties.getAccountsByCustomerTtl())
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(
                                        accountListSerializer)));
        cacheConfigurations.put(
                RECENT_TRANSACTIONS_CACHE,
                defaultConfig
                        .entryTtl(properties.getRecentTransactionsTtl())
                        .serializeValuesWith(
                                RedisSerializationContext.SerializationPair.fromSerializer(
                                        txPageSerializer)));
        cacheConfigurations.put(
                CUSTOMER_TYPES_CACHE, defaultConfig.entryTtl(properties.getCustomerTypesTtl()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.redis.enabled", havingValue = "false", matchIfMissing = true)
    public CacheManager caffeineCacheManager(CacheProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(
                Caffeine.newBuilder()
                        .maximumSize(properties.getMaximumSize())
                        .expireAfterWrite(properties.getCaffeineAccountsByCustomerTtl())
                        .recordStats());

        manager.registerCustomCache(
                ACCOUNTS_BY_CUSTOMER_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(properties.getMaximumSize())
                        .expireAfterWrite(properties.getCaffeineAccountsByCustomerTtl())
                        .recordStats()
                        .build());
        manager.registerCustomCache(
                RECENT_TRANSACTIONS_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(properties.getMaximumSize())
                        .expireAfterWrite(properties.getCaffeineRecentTransactionsTtl())
                        .recordStats()
                        .build());
        manager.registerCustomCache(
                CUSTOMER_TYPES_CACHE,
                Caffeine.newBuilder()
                        .maximumSize(properties.getMaximumSize())
                        .expireAfterWrite(properties.getCaffeineCustomerTypesTtl())
                        .recordStats()
                        .build());
        return manager;
    }

    static ObjectMapper createRedisObjectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }
}
