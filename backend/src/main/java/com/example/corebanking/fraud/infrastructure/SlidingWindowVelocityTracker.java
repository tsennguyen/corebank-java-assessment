package com.example.corebanking.fraud.infrastructure;

import com.example.corebanking.fraud.domain.FraudProperties;
import com.example.corebanking.fraud.domain.VelocityWindow;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Sliding-window transaction velocity tracker backed by Redis Sorted Set ({@code ZSET}: {@code
 * ZREMRANGEBYSCORE}, {@code ZADD}, {@code ZCARD}, {@code EXPIRE}) when {@code app.redis.enabled =
 * true}, with automatic resilient fallback to per-account {@link ArrayDeque} guarded by {@link
 * ReentrantLock} (avoiding {@code synchronized} to prevent Java 21 Virtual Thread pinning) (L3-03,
 * L3-10, FR-22, ADR-012, Rule 14).
 */
@Component
public class SlidingWindowVelocityTracker implements VelocityWindow {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowVelocityTracker.class);
    static final String KEY_PREFIX = "vel:";

    private final FraudProperties fraudProperties;
    private final Clock clock;
    @Nullable private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;

    private final Map<Long, AccountSlidingWindow> inMemoryWindows = new ConcurrentHashMap<>();

    @Autowired
    public SlidingWindowVelocityTracker(
            FraudProperties fraudProperties,
            Clock clock,
            @Nullable StringRedisTemplate redisTemplate,
            @Value("${app.redis.enabled:false}") boolean redisEnabled) {
        this.fraudProperties = fraudProperties;
        this.clock = clock;
        this.redisTemplate = redisTemplate;
        this.redisEnabled = redisEnabled && redisTemplate != null;
    }

    @Override
    public int recordAndCount(Long accountId, Long transactionId) {
        long windowSeconds = Math.max(1L, fraudProperties.getVelocity().getWindowSeconds());
        long nowMs = clock.instant().toEpochMilli();
        long windowStartMs = nowMs - (windowSeconds * 1000L);

        int inMemoryCount = recordInMemory(accountId, transactionId, nowMs, windowStartMs);

        if (redisEnabled) {
            try {
                String key = KEY_PREFIX + accountId;
                String member =
                        transactionId != null
                                ? transactionId.toString()
                                : UUID.randomUUID().toString();
                ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
                if (zSetOps != null) {
                    zSetOps.removeRangeByScore(
                            key, Double.NEGATIVE_INFINITY, (double) (windowStartMs - 1L));
                    zSetOps.add(key, member, (double) nowMs);
                    Long card = zSetOps.zCard(key);
                    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
                    if (card != null) {
                        return card.intValue();
                    }
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable during fraud velocity check [accountId={}]; falling"
                                + " back to in-memory ArrayDeque sliding window: {}",
                        accountId,
                        ex.getMessage());
            }
        }

        return inMemoryCount;
    }

    private int recordInMemory(Long accountId, Long transactionId, long nowMs, long windowStartMs) {
        AccountSlidingWindow window =
                inMemoryWindows.computeIfAbsent(accountId, id -> new AccountSlidingWindow());
        return window.recordAndCount(transactionId, nowMs, windowStartMs);
    }

    /** Clears all in-memory sliding windows (used by isolated integration tests). */
    public void clearInMemoryWindows() {
        inMemoryWindows.clear();
    }

    private static final class AccountSlidingWindow {

        private final ReentrantLock lock = new ReentrantLock();
        private final Deque<VelocityEntry> deque = new ArrayDeque<>();

        int recordAndCount(Long transactionId, long nowMs, long windowStartMs) {
            lock.lock();
            try {
                while (!deque.isEmpty() && deque.peekFirst().timestampMs() < windowStartMs) {
                    deque.pollFirst();
                }
                boolean alreadyRecorded =
                        transactionId != null
                                && deque.stream()
                                        .anyMatch(e -> transactionId.equals(e.transactionId()));
                if (!alreadyRecorded) {
                    deque.addLast(new VelocityEntry(transactionId, nowMs));
                }
                return deque.size();
            } finally {
                lock.unlock();
            }
        }
    }

    private record VelocityEntry(Long transactionId, long timestampMs) {}
}
