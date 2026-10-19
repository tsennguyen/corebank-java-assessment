package com.example.corebanking.auth.application;

import com.example.corebanking.auth.api.dto.LoginRequest;
import com.example.corebanking.auth.api.dto.TokenResponse;
import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.infrastructure.AppUserRepository;
import com.example.corebanking.auth.infrastructure.JwtTokenService;
import com.example.corebanking.auth.infrastructure.LoginRateLimiter;
import com.example.corebanking.auth.infrastructure.RefreshTokenStore;
import com.example.corebanking.auth.infrastructure.SecurityProperties;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Application service handling user authentication, JWT access token issuance, opaque refresh token
 * rotation with reuse detection, and brute-force login rate limiting (L2-01, L2-02, NC-07, FR-13,
 * FR-15, NFR-5).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password";
    private static final int MAX_BCRYPT_PASSWORD_BYTES = 72;

    /**
     * Precomputed BCrypt strength-10 hash used when a username is not found so that BCrypt
     * verification still executes, preventing timing-based username enumeration.
     */
    static final String DUMMY_BCRYPT_HASH =
            "$2a$10$3CQZNBArpgzgErJJdek7LuIOynWuxt9ELxJSkKVk6gxjvlQXNhMty";

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final Clock clock;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginRateLimiter loginRateLimiter;

    public AuthService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            Clock clock) {
        this(
                appUserRepository,
                passwordEncoder,
                jwtTokenService,
                clock,
                new RefreshTokenStore(new SecurityProperties(), clock, null, false),
                new LoginRateLimiter(new SecurityProperties(), clock, null, false));
    }

    @Autowired
    public AuthService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            Clock clock,
            RefreshTokenStore refreshTokenStore,
            LoginRateLimiter loginRateLimiter) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.clock = clock;
        this.refreshTokenStore = refreshTokenStore;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * Authenticates a user by username and password and issues a signed JWT access token plus an
     * opaque 256-bit refresh token.
     *
     * @param request the login credentials
     * @return the issued {@link TokenResponse}
     * @throws BusinessRuleException with {@link ErrorCode#UNAUTHORIZED} if credentials are invalid
     *     or the account is disabled
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        return login(request, resolveClientIpFromContext());
    }

    /**
     * Authenticates a user with explicit client IP rate-limiting protection before running BCrypt.
     *
     * @param request the login credentials
     * @param clientIp the caller IP address for brute-force rate limiting
     * @return the issued {@link TokenResponse}
     */
    @Transactional
    public TokenResponse login(LoginRequest request, String clientIp) {
        String normalizedUsername = request.username().trim();
        String rawPassword = request.password();

        loginRateLimiter.checkAllowed(normalizedUsername, clientIp);

        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_PASSWORD_BYTES) {
            passwordEncoder.matches("oversized-password", DUMMY_BCRYPT_HASH);
            loginRateLimiter.recordFailure(normalizedUsername, clientIp);
            log.warn("Failed login attempt with oversized password for '{}'", normalizedUsername);
            throw new BusinessRuleException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
        }

        Optional<AppUser> userOpt = appUserRepository.findByUsername(normalizedUsername);

        if (userOpt.isEmpty()) {
            passwordEncoder.matches(rawPassword, DUMMY_BCRYPT_HASH);
            loginRateLimiter.recordFailure(normalizedUsername, clientIp);
            log.warn("Failed login attempt for unknown username '{}'", normalizedUsername);
            throw new BusinessRuleException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
        }

        AppUser user = userOpt.get();
        boolean passwordMatches = passwordEncoder.matches(rawPassword, user.getPasswordHash());
        if (!passwordMatches || !user.isEnabled()) {
            loginRateLimiter.recordFailure(normalizedUsername, clientIp);
            log.warn(
                    "Failed login attempt for username '{}' (enabled={})",
                    normalizedUsername,
                    user.isEnabled());
            throw new BusinessRuleException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
        }

        loginRateLimiter.reset(normalizedUsername, clientIp);
        user.recordLogin(clock.instant());
        appUserRepository.save(user);

        String accessToken = jwtTokenService.generateAccessToken(user);
        String refreshToken = refreshTokenStore.issueNewFamilyToken(user.getId());
        log.info(
                "User '{}' (role={}) authenticated successfully",
                user.getUsername(),
                user.getRole());

        return TokenResponse.bearer(
                accessToken,
                refreshToken,
                jwtTokenService.getAccessTokenTtlSeconds(),
                user.getRole(),
                user.getCustomerId());
    }

    /**
     * Rotates a valid one-time refresh token into a new access token and refresh token in the same
     * family, or revokes the entire token family and throws {@code 401 TOKEN_REUSED} on replay.
     *
     * @param rawRefreshToken the opaque refresh token from request body or cookie
     * @return the newly issued {@link TokenResponse}
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String rawRefreshToken) {
        RefreshTokenStore.ConsumedRefreshToken consumed =
                refreshTokenStore.consumeForRotation(rawRefreshToken);

        AppUser user =
                appUserRepository
                        .findById(consumed.userId())
                        .orElseThrow(
                                () -> {
                                    refreshTokenStore.revokeFamily(consumed.familyId());
                                    return new BusinessRuleException(
                                            ErrorCode.UNAUTHORIZED,
                                            "User account no longer exists");
                                });

        if (!user.isEnabled()) {
            refreshTokenStore.revokeFamily(consumed.familyId());
            log.warn(
                    "Rejected refresh token for disabled user '{}' (family='{}')",
                    user.getUsername(),
                    consumed.familyId());
            throw new BusinessRuleException(ErrorCode.UNAUTHORIZED, "User account is disabled");
        }

        String accessToken = jwtTokenService.generateAccessToken(user);
        String newRefreshToken =
                refreshTokenStore.issueTokenInFamily(user.getId(), consumed.familyId());

        return TokenResponse.bearer(
                accessToken,
                newRefreshToken,
                jwtTokenService.getAccessTokenTtlSeconds(),
                user.getRole(),
                user.getCustomerId());
    }

    /**
     * Revokes the refresh token family associated with the given token on logout.
     *
     * @param rawRefreshToken the opaque refresh token from request body or cookie (nullable)
     */
    public void logout(String rawRefreshToken) {
        refreshTokenStore.revokeByToken(rawRefreshToken);
    }

    private static String resolveClientIpFromContext() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String remoteAddr = request.getRemoteAddr();
            String normalizedRemote = remoteAddr != null ? remoteAddr.trim() : "";
            if (isTrustedProxy(normalizedRemote)) {
                String forwardedFor = request.getHeader("X-Forwarded-For");
                if (forwardedFor != null && !forwardedFor.isBlank()) {
                    int commaIdx = forwardedFor.indexOf(',');
                    return (commaIdx > 0 ? forwardedFor.substring(0, commaIdx) : forwardedFor)
                            .trim();
                }
            }
            if (!normalizedRemote.isEmpty()) {
                return normalizedRemote;
            }
        }
        return "unknown";
    }

    static boolean isTrustedProxy(String ip) {
        if (ip == null || ip.isEmpty()) {
            return true;
        }
        return ip.startsWith("127.")
                || "::1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)
                || ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")
                || ip.matches("^100\\.(6[4-9]|[7-9][0-9]|1[0-1][0-9]|12[0-7])\\..*");
    }
}
