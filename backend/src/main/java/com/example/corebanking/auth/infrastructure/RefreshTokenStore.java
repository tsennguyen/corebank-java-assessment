package com.example.corebanking.auth.infrastructure;

import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Stores and rotates 256-bit opaque refresh tokens using SHA-256 hashing, token family tracking,
 * and reuse detection ({@code 401 TOKEN_REUSED}) with dual Redis + in-memory fallback support
 * (NC-07, FR-15). Uses {@link ReentrantLock} instead of {@code synchronized} to avoid Java 21
 * Virtual Thread carrier pinning during Redis I/O (L3-10, NFR-4).
 */
@Component
public class RefreshTokenStore {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenStore.class);

    private static final int TOKEN_BYTE_LENGTH = 32;
    private static final String KEY_ACTIVE_PREFIX = "rt:";
    private static final String KEY_USED_PREFIX = "rt:used:";
    private static final String KEY_REVOKED_FAMILY_PREFIX = "rt:revoked:";

    private final SecurityProperties securityProperties;
    private final Clock clock;
    @Nullable private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ReentrantLock rotationLock = new ReentrantLock();

    private final Map<String, ActiveTokenEntry> activeTokens = new ConcurrentHashMap<>();
    private final Map<String, UsedTokenEntry> usedTokens = new ConcurrentHashMap<>();
    private final Map<String, Instant> revokedFamilies = new ConcurrentHashMap<>();

    @Autowired
    public RefreshTokenStore(
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
     * Issues a new 256-bit opaque refresh token starting a brand-new token family (on user login).
     *
     * @param userId the authenticated user ID
     * @return the raw Base64URL-encoded refresh token (never stored in plaintext)
     */
    public String issueNewFamilyToken(Long userId) {
        return issueTokenInFamily(userId, UUID.randomUUID().toString());
    }

    /**
     * Issues a new 256-bit opaque refresh token within an existing token family (on token
     * rotation).
     *
     * @param userId the authenticated user ID
     * @param familyId the token family identifier
     * @return the raw Base64URL-encoded refresh token (never stored in plaintext)
     */
    public String issueTokenInFamily(Long userId, String familyId) {
        byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(randomBytes);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = sha256Hex(rawToken);

        long ttlSeconds = Math.max(1L, securityProperties.getJwt().getRefreshTokenTtlSeconds());
        Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);

        ActiveTokenEntry entry = new ActiveTokenEntry(userId, familyId, expiresAt);
        activeTokens.put(tokenHash, entry);

        if (redisEnabled) {
            try {
                String payload = userId + "|" + familyId + "|" + expiresAt.toEpochMilli();
                redisTemplate
                        .opsForValue()
                        .set(
                                KEY_ACTIVE_PREFIX + tokenHash,
                                payload,
                                Duration.ofSeconds(ttlSeconds));
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while storing refresh token; falling back to in-memory"
                                + " store: {}",
                        ex.getMessage());
            }
        }

        return rawToken;
    }

    /**
     * Atomically consumes a presented refresh token for rotation, or revokes the entire token
     * family and throws {@link ErrorCode#TOKEN_REUSED} if the token was already used.
     *
     * @param rawToken the raw refresh token presented by the client
     * @return the consumed token metadata ({@code userId} and {@code familyId})
     */
    public ConsumedRefreshToken consumeForRotation(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessRuleException(ErrorCode.UNAUTHORIZED, "Refresh token is required");
        }

        String tokenHash = sha256Hex(rawToken.trim());
        Instant now = clock.instant();

        rotationLock.lock();
        try {
            String reusedFamilyId = findUsedTokenFamily(tokenHash, now);
            if (reusedFamilyId != null) {
                revokeFamily(reusedFamilyId);
                log.warn(
                        "Detected refresh token reuse for family '{}'; entire token family revoked",
                        reusedFamilyId);
                throw new BusinessRuleException(
                        ErrorCode.TOKEN_REUSED, ErrorCode.TOKEN_REUSED.getDefaultTitle());
            }

            ActiveTokenEntry activeEntry = removeActiveToken(tokenHash);
            if (activeEntry == null || now.isAfter(activeEntry.expiresAt())) {
                throw new BusinessRuleException(
                        ErrorCode.UNAUTHORIZED, "Invalid or expired refresh token");
            }

            if (isFamilyRevoked(activeEntry.familyId(), now)) {
                throw new BusinessRuleException(
                        ErrorCode.UNAUTHORIZED, "Refresh token session has been revoked");
            }

            markTokenUsed(tokenHash, activeEntry.familyId(), activeEntry.expiresAt(), now);
            return new ConsumedRefreshToken(activeEntry.userId(), activeEntry.familyId());
        } finally {
            rotationLock.unlock();
        }
    }

    /**
     * Revokes the token family associated with the given raw refresh token (used on {@code
     * /logout}).
     *
     * @param rawToken the raw refresh token from request body or cookie (nullable)
     */
    public void revokeByToken(@Nullable String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String tokenHash = sha256Hex(rawToken.trim());
        Instant now = clock.instant();

        rotationLock.lock();
        try {
            ActiveTokenEntry activeEntry = removeActiveToken(tokenHash);
            if (activeEntry != null) {
                revokeFamily(activeEntry.familyId());
                return;
            }
            String usedFamilyId = findUsedTokenFamily(tokenHash, now);
            if (usedFamilyId != null) {
                revokeFamily(usedFamilyId);
            }
        } finally {
            rotationLock.unlock();
        }
    }

    /** Marks an entire token family as revoked for the remainder of the refresh token TTL. */
    public void revokeFamily(String familyId) {
        if (familyId == null || familyId.isBlank()) {
            return;
        }
        long ttlSeconds = Math.max(1L, securityProperties.getJwt().getRefreshTokenTtlSeconds());
        Instant expiresAt = clock.instant().plusSeconds(ttlSeconds);
        revokedFamilies.put(familyId, expiresAt);

        activeTokens.entrySet().removeIf(entry -> familyId.equals(entry.getValue().familyId()));

        if (redisEnabled) {
            try {
                redisTemplate
                        .opsForValue()
                        .set(
                                KEY_REVOKED_FAMILY_PREFIX + familyId,
                                String.valueOf(expiresAt.toEpochMilli()),
                                Duration.ofSeconds(ttlSeconds));
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while revoking token family '{}': {}",
                        familyId,
                        ex.getMessage());
            }
        }
    }

    /** Computes the lowercase SHA-256 hex digest of a raw refresh token. */
    public static String sha256Hex(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    /**
     * Returns true if the plaintext token exists as a key in the store (used by security tests).
     */
    public boolean containsPlaintextKey(String rawToken) {
        return activeTokens.containsKey(rawToken) || usedTokens.containsKey(rawToken);
    }

    /** Returns true if the SHA-256 hash of the token is currently active in memory. */
    public boolean containsHashedKey(String rawToken) {
        return activeTokens.containsKey(sha256Hex(rawToken));
    }

    private ActiveTokenEntry removeActiveToken(String tokenHash) {
        ActiveTokenEntry inMemory = activeTokens.remove(tokenHash);
        if (redisEnabled) {
            try {
                String redisKey = KEY_ACTIVE_PREFIX + tokenHash;
                String payload = redisTemplate.opsForValue().get(redisKey);
                redisTemplate.delete(redisKey);
                if (payload != null) {
                    ActiveTokenEntry parsed = parseActiveEntry(payload);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while consuming refresh token; using in-memory fallback:"
                                + " {}",
                        ex.getMessage());
            }
        }
        return inMemory;
    }

    private void markTokenUsed(String tokenHash, String familyId, Instant expiresAt, Instant now) {
        long remainingSeconds = Math.max(1L, Duration.between(now, expiresAt).getSeconds());
        usedTokens.put(tokenHash, new UsedTokenEntry(familyId, expiresAt));
        if (redisEnabled) {
            try {
                String payload = familyId + "|" + expiresAt.toEpochMilli();
                redisTemplate
                        .opsForValue()
                        .set(
                                KEY_USED_PREFIX + tokenHash,
                                payload,
                                Duration.ofSeconds(remainingSeconds));
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while marking refresh token used; using in-memory"
                                + " fallback: {}",
                        ex.getMessage());
            }
        }
    }

    @Nullable
    private String findUsedTokenFamily(String tokenHash, Instant now) {
        UsedTokenEntry inMemory = usedTokens.get(tokenHash);
        if (inMemory != null) {
            if (now.isAfter(inMemory.expiresAt())) {
                usedTokens.remove(tokenHash);
            } else {
                return inMemory.familyId();
            }
        }
        if (redisEnabled) {
            try {
                String payload = redisTemplate.opsForValue().get(KEY_USED_PREFIX + tokenHash);
                if (payload != null) {
                    int sep = payload.indexOf('|');
                    return sep > 0 ? payload.substring(0, sep) : payload;
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while checking used refresh token; using in-memory"
                                + " fallback: {}",
                        ex.getMessage());
            }
        }
        return null;
    }

    private boolean isFamilyRevoked(String familyId, Instant now) {
        Instant revokedUntil = revokedFamilies.get(familyId);
        if (revokedUntil != null) {
            if (now.isAfter(revokedUntil)) {
                revokedFamilies.remove(familyId);
            } else {
                return true;
            }
        }
        if (redisEnabled) {
            try {
                String payload =
                        redisTemplate.opsForValue().get(KEY_REVOKED_FAMILY_PREFIX + familyId);
                if (payload != null) {
                    return true;
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "Redis unavailable while checking revoked family '{}'; using in-memory"
                                + " fallback: {}",
                        familyId,
                        ex.getMessage());
            }
        }
        return false;
    }

    @Nullable
    private static ActiveTokenEntry parseActiveEntry(String payload) {
        String[] parts = payload.split("\\|");
        if (parts.length != 3) {
            return null;
        }
        try {
            Long userId = Long.parseLong(parts[0]);
            String familyId = parts[1];
            Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
            return new ActiveTokenEntry(userId, familyId, expiresAt);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record ConsumedRefreshToken(Long userId, String familyId) {}

    private record ActiveTokenEntry(Long userId, String familyId, Instant expiresAt) {}

    private record UsedTokenEntry(String familyId, Instant expiresAt) {}
}
