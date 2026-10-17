package com.example.corebanking.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties governing cache sizing and TTLs for Redis and Caffeine fallback (L2-08,
 * G-05, NFR-3, ADR-009).
 */
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private long maximumSize = 10_000L;
    private Duration accountsByCustomerTtl = Duration.ofMinutes(10);
    private Duration recentTransactionsTtl = Duration.ofMinutes(5);
    private Duration customerTypesTtl = Duration.ofMinutes(60);
    private Duration caffeineAccountsByCustomerTtl = Duration.ofMinutes(2);
    private Duration caffeineRecentTransactionsTtl = Duration.ofMinutes(1);
    private Duration caffeineCustomerTypesTtl = Duration.ofMinutes(60);

    public long getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(long maximumSize) {
        this.maximumSize = maximumSize;
    }

    public Duration getAccountsByCustomerTtl() {
        return accountsByCustomerTtl;
    }

    public void setAccountsByCustomerTtl(Duration accountsByCustomerTtl) {
        this.accountsByCustomerTtl = accountsByCustomerTtl;
    }

    public Duration getRecentTransactionsTtl() {
        return recentTransactionsTtl;
    }

    public void setRecentTransactionsTtl(Duration recentTransactionsTtl) {
        this.recentTransactionsTtl = recentTransactionsTtl;
    }

    public Duration getCustomerTypesTtl() {
        return customerTypesTtl;
    }

    public void setCustomerTypesTtl(Duration customerTypesTtl) {
        this.customerTypesTtl = customerTypesTtl;
    }

    public Duration getCaffeineAccountsByCustomerTtl() {
        return caffeineAccountsByCustomerTtl;
    }

    public void setCaffeineAccountsByCustomerTtl(Duration caffeineAccountsByCustomerTtl) {
        this.caffeineAccountsByCustomerTtl = caffeineAccountsByCustomerTtl;
    }

    public Duration getCaffeineRecentTransactionsTtl() {
        return caffeineRecentTransactionsTtl;
    }

    public void setCaffeineRecentTransactionsTtl(Duration caffeineRecentTransactionsTtl) {
        this.caffeineRecentTransactionsTtl = caffeineRecentTransactionsTtl;
    }

    public Duration getCaffeineCustomerTypesTtl() {
        return caffeineCustomerTypesTtl;
    }

    public void setCaffeineCustomerTypesTtl(Duration caffeineCustomerTypesTtl) {
        this.caffeineCustomerTypesTtl = caffeineCustomerTypesTtl;
    }
}
