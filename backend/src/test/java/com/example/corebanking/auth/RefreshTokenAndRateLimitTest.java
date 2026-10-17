package com.example.corebanking.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.auth.api.dto.LoginRequest;
import com.example.corebanking.auth.api.dto.RefreshTokenRequest;
import com.example.corebanking.auth.api.dto.TokenResponse;
import com.example.corebanking.auth.application.AuthService;
import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.domain.RateLimitExceededException;
import com.example.corebanking.auth.infrastructure.AppUserRepository;
import com.example.corebanking.auth.infrastructure.JwtTokenService;
import com.example.corebanking.auth.infrastructure.LoginRateLimiter;
import com.example.corebanking.auth.infrastructure.RefreshTokenStore;
import com.example.corebanking.auth.infrastructure.SecurityProperties;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link RefreshTokenStore}, {@link LoginRateLimiter}, and {@link AuthService}
 * covering SHA-256 token hashing, one-time refresh token rotation, reuse detection revoking the
 * entire token family ({@code 401 TOKEN_REUSED}), brute-force login rate limiting ({@code 429
 * TOO_MANY_REQUESTS}), and Redis + in-memory fallback (Story 3.8: NC-07, FR-15, NFR-5).
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenAndRateLimitTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private AtomicReference<Instant> currentInstant;
    private Clock mutableClock;
    private SecurityProperties securityProperties;

    @BeforeEach
    void setUp() {
        currentInstant = new AtomicReference<>(Instant.parse("2026-10-17T02:00:00Z"));
        mutableClock =
                new Clock() {
                    @Override
                    public ZoneOffset getZone() {
                        return ZoneOffset.UTC;
                    }

                    @Override
                    public Clock withZone(java.time.ZoneId zone) {
                        return this;
                    }

                    @Override
                    public Instant instant() {
                        return currentInstant.get();
                    }
                };
        securityProperties = new SecurityProperties();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName(
            "RefreshTokenStore stores only SHA-256 hash (never plaintext), rotates R1 -> R2 in"
                    + " same family, and revokes entire family when R1 is reused (AC 1, AC 2)")
    void refreshTokenStore_rotationAndReuseDetection_shouldRevokeEntireFamily() {
        RefreshTokenStore store =
                new RefreshTokenStore(securityProperties, mutableClock, null, false);

        String r1 = store.issueNewFamilyToken(10L);
        assertThat(r1).hasSizeGreaterThanOrEqualTo(43);
        assertThat(store.containsPlaintextKey(r1)).isFalse();
        assertThat(store.containsHashedKey(r1)).isTrue();

        // 1. Rotate R1 -> R2 within the same family
        RefreshTokenStore.ConsumedRefreshToken consumed1 = store.consumeForRotation(r1);
        assertThat(consumed1.userId()).isEqualTo(10L);
        assertThat(consumed1.familyId()).isNotBlank();

        String r2 = store.issueTokenInFamily(10L, consumed1.familyId());
        assertThat(r2).isNotEqualTo(r1);
        assertThat(store.containsHashedKey(r1)).isFalse();
        assertThat(store.containsHashedKey(r2)).isTrue();

        // 2. Replaying already-used R1 triggers 401 TOKEN_REUSED and revokes the entire family
        assertThatThrownBy(() -> store.consumeForRotation(r1))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex -> {
                            BusinessRuleException bre = (BusinessRuleException) ex;
                            assertThat(bre.getErrorCode()).isEqualTo(ErrorCode.TOKEN_REUSED);
                        });

        // 3. R2 (from the same family) is now immediately invalidated
        assertThatThrownBy(() -> store.consumeForRotation(r2))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex -> {
                            BusinessRuleException bre = (BusinessRuleException) ex;
                            assertThat(bre.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                        });
    }

    @Test
    @DisplayName(
            "RefreshTokenStore rejects expired token and revokes family on logout (AC 2, AC 3)")
    void refreshTokenStore_expirationAndLogout_shouldInvalidateToken() {
        RefreshTokenStore store =
                new RefreshTokenStore(securityProperties, mutableClock, null, false);

        String r1 = store.issueNewFamilyToken(10L);
        store.revokeByToken(r1);

        assertThatThrownBy(() -> store.consumeForRotation(r1))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        String r2 = store.issueNewFamilyToken(10L);
        currentInstant.set(currentInstant.get().plusSeconds(604801L));

        assertThatThrownBy(() -> store.consumeForRotation(r2))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName(
            "RefreshTokenStore in Redis mode writes/reads keys and falls back gracefully when"
                    + " Redis throws runtime exception (AC 5)")
    void refreshTokenStore_redisModeAndFallback_shouldDegradeGracefully() {
        RefreshTokenStore store =
                new RefreshTokenStore(securityProperties, mutableClock, redisTemplate, true);

        String r1 = store.issueNewFamilyToken(42L);
        String hash1 = RefreshTokenStore.sha256Hex(r1);
        verify(valueOperations)
                .set(eq("rt:" + hash1), anyString(), eq(Duration.ofSeconds(604800L)));

        // Simulate Redis outage during rotation: store must fall back to in-memory state seamlessly
        doThrow(new RedisConnectionFailureException("Redis down"))
                .when(valueOperations)
                .get(anyString());

        RefreshTokenStore.ConsumedRefreshToken consumed = store.consumeForRotation(r1);
        assertThat(consumed.userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName(
            "LoginRateLimiter blocks 6th attempt after 5 failures within 5 minutes, resets on"
                    + " success, and normalizes username/IP (AC 4)")
    void loginRateLimiter_shouldEnforceMaxAttemptsAndResetOnSuccess() {
        LoginRateLimiter limiter =
                new LoginRateLimiter(securityProperties, mutableClock, null, false);

        for (int i = 0; i < 5; i++) {
            limiter.checkAllowed("  Admin ", "10.0.0.5");
            limiter.recordFailure("admin", "10.0.0.5");
        }

        assertThatThrownBy(() -> limiter.checkAllowed("ADMIN", "10.0.0.5"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(
                        ex -> {
                            RateLimitExceededException rle = (RateLimitExceededException) ex;
                            assertThat(rle.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                            assertThat(rle.getRetryAfterSeconds()).isEqualTo(300L);
                        });

        // Different IP is not blocked
        limiter.checkAllowed("admin", "10.0.0.6");

        // Advance clock past 300s window -> unblocked
        currentInstant.set(currentInstant.get().plusSeconds(301L));
        limiter.checkAllowed("admin", "10.0.0.5");

        // 3 failures followed by reset (successful login) clears counter
        limiter.recordFailure("cust.an", "10.0.0.5");
        limiter.recordFailure("cust.an", "10.0.0.5");
        limiter.recordFailure("cust.an", "10.0.0.5");
        limiter.reset("CUST.AN", "10.0.0.5");
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("cust.an", "10.0.0.5");
        }
        limiter.checkAllowed("cust.an", "10.0.0.5");
    }

    @Test
    @DisplayName(
            "LoginRateLimiter in Redis mode enforces Redis counter TTL and falls back to in-memory"
                    + " when Redis fails (AC 4, AC 5)")
    void loginRateLimiter_redisModeAndFallback_shouldWork() {
        LoginRateLimiter limiter =
                new LoginRateLimiter(securityProperties, mutableClock, redisTemplate, true);

        when(valueOperations.increment("rl:login:admin|10.0.0.1")).thenReturn(1L);
        limiter.recordFailure("admin", "10.0.0.1");
        verify(redisTemplate).expire("rl:login:admin|10.0.0.1", Duration.ofSeconds(300L));

        when(valueOperations.get("rl:login:admin|10.0.0.1")).thenReturn("5");
        when(redisTemplate.getExpire("rl:login:admin|10.0.0.1", TimeUnit.SECONDS)).thenReturn(180L);

        assertThatThrownBy(() -> limiter.checkAllowed("admin", "10.0.0.1"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(
                        ex ->
                                assertThat(((RateLimitExceededException) ex).getRetryAfterSeconds())
                                        .isEqualTo(180L));

        // Simulate Redis failure -> falls back to in-memory window
        when(valueOperations.get("rl:login:admin|10.0.0.1"))
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        limiter.checkAllowed("admin", "10.0.0.1");
    }

    @Test
    @DisplayName(
            "AuthService blocks 6th login attempt BEFORE BCrypt execution and rejects refresh when"
                    + " user becomes disabled (AC 2, AC 4)")
    void authService_rateLimitBeforeBcrypt_andRefreshRejectsDisabledUser() {
        RefreshTokenStore tokenStore =
                new RefreshTokenStore(securityProperties, mutableClock, null, false);
        LoginRateLimiter rateLimiter =
                new LoginRateLimiter(securityProperties, mutableClock, null, false);
        AuthService authService =
                new AuthService(
                        appUserRepository,
                        passwordEncoder,
                        jwtTokenService,
                        mutableClock,
                        tokenStore,
                        rateLimiter);

        AppUser user = AppUser.createCustomer("cust.an", "$2a$10$hash", 15L);
        ReflectionTestUtils.setField(user, "id", 7L);
        when(appUserRepository.findByUsername("cust.an")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$10$hash")).thenReturn(false);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(
                            () ->
                                    authService.login(
                                            new LoginRequest("cust.an", "wrong"), "192.168.1.50"))
                    .isInstanceOf(BusinessRuleException.class);
        }

        // 6th attempt even with valid password is blocked BEFORE
        // passwordEncoder.matches("Customer@123", ...)
        assertThatThrownBy(
                        () ->
                                authService.login(
                                        new LoginRequest("cust.an", "Customer@123"),
                                        "192.168.1.50"))
                .isInstanceOf(RateLimitExceededException.class);
        verify(passwordEncoder, never()).matches(eq("Customer@123"), any());

        // Login from another IP succeeds and issues refreshToken
        when(passwordEncoder.matches("Customer@123", "$2a$10$hash")).thenReturn(true);
        when(jwtTokenService.generateAccessToken(user)).thenReturn("jwt.1");
        when(jwtTokenService.getAccessTokenTtlSeconds()).thenReturn(900L);

        TokenResponse loginResp =
                authService.login(new LoginRequest("cust.an", "Customer@123"), "192.168.1.99");
        assertThat(loginResp.refreshToken()).isNotBlank();
        assertThat(new RefreshTokenRequest(loginResp.refreshToken()).toString())
                .doesNotContain(loginResp.refreshToken())
                .contains("***");

        // If user is disabled before refresh, refresh revokes family and throws 401 UNAUTHORIZED
        user.disable();
        when(appUserRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.refresh(loginResp.refreshToken()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting(ex -> ((BusinessRuleException) ex).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }
}
