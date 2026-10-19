package com.example.corebanking.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Resilient {@link CacheErrorHandler} that logs and swallows runtime cache failures (e.g. Redis
 * connection drops or command timeouts) so read/write paths degrade gracefully to PostgreSQL
 * without failing client HTTP requests (L2-08, NFR-3, ADR-009).
 */
public class ResilientCacheErrorHandler implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ResilientCacheErrorHandler.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn(
                "Cache GET failed [cache={}, key={}]; falling back to database: {}",
                resolveCacheName(cache),
                key,
                exception.getMessage());
    }

    @Override
    public void handleCachePutError(
            RuntimeException exception, Cache cache, Object key, Object value) {
        log.warn(
                "Cache PUT failed [cache={}, key={}]; continuing without caching: {}",
                resolveCacheName(cache),
                key,
                exception.getMessage());
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn(
                "Cache EVICT failed [cache={}, key={}]; entry will expire via TTL: {}",
                resolveCacheName(cache),
                key,
                exception.getMessage());
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn(
                "Cache CLEAR failed [cache={}]; entries will expire via TTL: {}",
                resolveCacheName(cache),
                exception.getMessage());
    }

    private static String resolveCacheName(Cache cache) {
        return cache != null ? cache.getName() : "unknown";
    }
}
