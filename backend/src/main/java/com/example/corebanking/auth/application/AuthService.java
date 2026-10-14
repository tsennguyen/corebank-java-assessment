package com.example.corebanking.auth.application;

import com.example.corebanking.auth.api.dto.LoginRequest;
import com.example.corebanking.auth.api.dto.TokenResponse;
import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.infrastructure.AppUserRepository;
import com.example.corebanking.auth.infrastructure.JwtTokenService;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service handling user authentication and JWT issuance with timing-safe credential
 * verification (L2-01, L2-02, FR-13).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public static final String INVALID_CREDENTIALS_MESSAGE = "Invalid username or password";

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

    public AuthService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService,
            Clock clock) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.clock = clock;
    }

    /**
     * Authenticates a user by username and password and issues a signed JWT access token.
     *
     * @param request the login credentials
     * @return the issued {@link TokenResponse}
     * @throws BusinessRuleException with {@link ErrorCode#UNAUTHORIZED} if credentials are invalid
     *     or the account is disabled
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String normalizedUsername = request.username().trim();
        Optional<AppUser> userOpt = appUserRepository.findByUsername(normalizedUsername);

        if (userOpt.isEmpty()) {
            passwordEncoder.matches(request.password(), DUMMY_BCRYPT_HASH);
            log.warn("Failed login attempt for unknown username '{}'", normalizedUsername);
            throw new BusinessRuleException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
        }

        AppUser user = userOpt.get();
        boolean passwordMatches =
                passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (!passwordMatches || !user.isEnabled()) {
            log.warn(
                    "Failed login attempt for username '{}' (enabled={})",
                    normalizedUsername,
                    user.isEnabled());
            throw new BusinessRuleException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
        }

        user.recordLogin(clock.instant());
        appUserRepository.save(user);

        String accessToken = jwtTokenService.generateAccessToken(user);
        log.info(
                "User '{}' (role={}) authenticated successfully",
                user.getUsername(),
                user.getRole());

        return TokenResponse.bearer(
                accessToken,
                jwtTokenService.getAccessTokenTtlSeconds(),
                user.getRole(),
                user.getCustomerId());
    }
}
