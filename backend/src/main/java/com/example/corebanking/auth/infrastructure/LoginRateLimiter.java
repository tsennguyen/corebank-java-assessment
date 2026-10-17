package com.example.corebanking.auth.infrastructure;

import com.example.corebanking.auth.domain.RateLimitExceededException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Brute-force login rate limiter tracking consecutive failed authentication attempts per normalized
 * {@code (username, clientIp)} tuple over a sliding/fixed TTL window with dual Redis + in-memory
 * fallback (NC-07, NFR-5).
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    private static final String KEY_PREFIX = "rl:login:";

    private final SecurityProperties securityProperties;
    private final Clock clock;
    @Nullable private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;

    private final Map<String, WindowEntry> inMemoryWindows = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(
            SecurityProperties securityProperties,
            Clock clock,
            @Nullable StringRedisTemplate redisTemplate,
            @Value("${app.redis.enabled:false}") boolean redisEnabled) {
        this.securityProperties = securityProperties;
        this.clock = clock;
        this.redisTemplate = redisTemplate;
        this.redisEnabled = redisEnabled && redisTemplate != null;
    }

    /**
     * Verifies that the {@code (username, clientIp)} tuple has not exceeded the configured maximum
     * failed login attempts within the active rate-limit window.
     *
     * @throws RateLimitExceededException with HTTP 429 and {@code retryAfterSeconds} when blocked
     */
    public void checkAllowed(String username, String clientIp) {
        int maxAttempts = Math.max(1, securityProperties.getRateLimit().getMaxAttempts());
        long windowSeconds = Math.max(1L, securityProperties.getRateLimit().getWindowSeconds());
        String key = buildKey(username, clientIp);
        Instant now = clock.instant();

        if (redisEnabled) {
            try {
                String rawCount = redisTemplate.opsForValue().get(key);
                if (rawCount != null) {
                    int attempts = Integer.parseInt(rawCount);
                    if (attempts >= maxAttempts) {
                        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                        long retryAfter = (ttl != null && ttl > 0) ? ttl : windowSeconds;
                        throw new RateLimitExceededException(
                                "Too many failed login attempts. Please try again in "
                                        + retryAfter
                                        + " seconds.",
                                retryAfter);
                    }
                }
                return;
            } catch (RateLimitExceededException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable during login rate-limit check; falling back to"
                                + " in-memory limiter: {}",
                        ex.getMessage());
            }
        }

        WindowEntry entry = inMemoryWindows.get(key);
        if (entry != null) {
            if (!now.isBefore(entry.expiresAt())) {
                inMemoryWindows.remove(key, entry);
                return;
            }
            if (entry.attempts() >= maxAttempts) {
                long retryAfter =
                        Math.max(1L, Duration.between(now, entry.expiresAt()).getSeconds());
                throw new RateLimitExceededException(
                        "Too many failed login attempts. Please try again in "
                                + retryAfter
                                + " seconds.",
                        retryAfter);
            }
        }
    }

    /** Records a failed login attempt for the given {@code (username, clientIp)} tuple. */
    public void recordFailure(String username, String clientIp) {
        long windowSeconds = Math.max(1L, securityProperties.getRateLimit().getWindowSeconds());
        String key = buildKey(username, clientIp);
        Instant now = clock.instant();

        inMemoryWindows.compute(
                key,
                (k, existing) -> {
                    if (existing == null || !now.isBefore(existing.expiresAt())) {
                        return new WindowEntry(1, now.plusSeconds(windowSeconds));
                    }
                    return new WindowEntry(existing.attempts() + 1, existing.expiresAt());
                });

        if (redisEnabled) {
            try {
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1L) {
                    redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while recording failed login attempt; using in-memory"
                                + " limiter: {}",
                        ex.getMessage());
            }
        }
    }

    /**
     * Resets the failed login counter for {@code (username, clientIp)} after a successful login.
     */
    public void reset(String username, String clientIp) {
        String key = buildKey(username, clientIp);
        inMemoryWindows.remove(key);
        if (redisEnabled) {
            try {
                redisTemplate.delete(key);
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while resetting login rate-limit counter: {}",
                        ex.getMessage());
            }
        }
    }

    static String buildKey(String username, String clientIp) {
        String normalizedUser = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        String normalizedIp =
                (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
        return KEY_PREFIX + normalizedUser + "|" + normalizedIp;
    }

    private record WindowEntry(int attempts, Instant expiresAt) {}
}
