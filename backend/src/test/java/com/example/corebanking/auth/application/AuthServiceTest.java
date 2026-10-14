package com.example.corebanking.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.corebanking.auth.api.dto.LoginRequest;
import com.example.corebanking.auth.api.dto.TokenResponse;
import com.example.corebanking.auth.domain.AppUser;
import com.example.corebanking.auth.domain.Role;
import com.example.corebanking.auth.infrastructure.AppUserRepository;
import com.example.corebanking.auth.infrastructure.JwtTokenService;
import com.example.corebanking.auth.infrastructure.SecurityProperties;
import com.example.corebanking.common.domain.BusinessRuleException;
import com.example.corebanking.common.domain.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private AppUserRepository appUserRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenService jwtTokenService;

    private Clock fixedClock;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-14T08:00:00Z"), ZoneOffset.UTC);
        authService =
                new AuthService(appUserRepository, passwordEncoder, jwtTokenService, fixedClock);
    }

    @Test
    @DisplayName(
            "login with valid credentials issues Bearer JWT, updates lastLoginAt, and returns"
                    + " TokenResponse")
    void login_whenCredentialsValid_shouldReturnTokenResponseAndRecordLogin() {
        AppUser customer = AppUser.createCustomer("cust.an", "$2a$10$hash", 15L);
        when(appUserRepository.findByUsername("cust.an")).thenReturn(Optional.of(customer));
        when(passwordEncoder.matches("Customer@123", "$2a$10$hash")).thenReturn(true);
        when(jwtTokenService.generateAccessToken(customer)).thenReturn("signed.jwt.token");
        when(jwtTokenService.getAccessTokenTtlSeconds()).thenReturn(900L);

        TokenResponse response = authService.login(new LoginRequest("  cust.an  ", "Customer@123"));

        assertThat(response.accessToken()).isEqualTo("signed.jwt.token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900L);
        assertThat(response.role()).isEqualTo(Role.CUSTOMER);
        assertThat(response.customerId()).isEqualTo(15L);
        assertThat(customer.getLastLoginAt()).isEqualTo(fixedClock.instant());
        verify(appUserRepository).save(customer);
    }

    @Test
    @DisplayName(
            "login with unknown username executes dummy BCrypt check and throws UNAUTHORIZED with"
                    + " generic message")
    void login_whenUsernameUnknown_shouldPerformDummyHashCheckAndThrowUnauthorized() {
        when(appUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "wrongPass")))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex -> {
                            BusinessRuleException bre = (BusinessRuleException) ex;
                            assertThat(bre.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                            assertThat(bre.getMessage())
                                    .isEqualTo(AuthService.INVALID_CREDENTIALS_MESSAGE);
                        });

        verify(passwordEncoder).matches(eq("wrongPass"), eq(AuthService.DUMMY_BCRYPT_HASH));
        verify(jwtTokenService, never()).generateAccessToken(any());
    }

    @Test
    @DisplayName(
            "login with wrong password or disabled user throws UNAUTHORIZED with identical generic"
                    + " message")
    void login_whenWrongPasswordOrDisabled_shouldThrowIdenticalUnauthorizedException() {
        AppUser user = AppUser.createAdmin("admin", "$2a$10$hash");
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("badPass", "$2a$10$hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "badPass")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS_MESSAGE);

        user.disable();
        when(passwordEncoder.matches("Admin@123", "$2a$10$hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", "Admin@123")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage(AuthService.INVALID_CREDENTIALS_MESSAGE);
    }

    @Test
    @DisplayName("Seed BCrypt hash in R__demo_seed.sql matches Customer@123 with strength 10")
    void seedBcryptHash_shouldMatchDemoCustomerPassword() {
        BCryptPasswordEncoder realEncoder = new BCryptPasswordEncoder(10);
        assertThat(realEncoder.matches("Customer@123", AuthService.DUMMY_BCRYPT_HASH)).isTrue();
    }

    @Test
    @DisplayName("AdminBootstrapRunner creates or synchronizes ADMIN user idempotently")
    void adminBootstrapRunner_shouldCreateAndSynchronizeAdminUser() {
        SecurityProperties props = new SecurityProperties();
        props.getAdminBootstrap().setEnabled(true);
        props.getAdminBootstrap().setUsername("admin");
        props.getAdminBootstrap().setPassword("Admin@123");

        AdminBootstrapRunner runner =
                new AdminBootstrapRunner(appUserRepository, passwordEncoder, props);

        // 1. Creates new admin when absent
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Admin@123")).thenReturn("$2a$10$encodedAdmin");
        runner.run(new DefaultApplicationArguments());
        verify(appUserRepository).save(any(AppUser.class));

        // 2. Updates existing admin when password changed or disabled
        AppUser existing = AppUser.createAdmin("admin", "$2a$10$oldHash");
        existing.disable();
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches("Admin@123", "$2a$10$oldHash")).thenReturn(false);
        when(passwordEncoder.encode("Admin@123")).thenReturn("$2a$10$newAdminHash");

        runner.run(new DefaultApplicationArguments());
        assertThat(existing.getPasswordHash()).isEqualTo("$2a$10$newAdminHash");
        assertThat(existing.isEnabled()).isTrue();

        // 3. Throws IllegalStateException if bootstrap username belongs to a CUSTOMER
        AppUser conflictCustomer = AppUser.createCustomer("admin", "$2a$10$hash", 99L);
        when(appUserRepository.findByUsername("admin")).thenReturn(Optional.of(conflictCustomer));
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-ADMIN role");

        // 4. Throws IllegalStateException if username or password is blank when enabled
        props.getAdminBootstrap().setPassword("   ");
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);

        // 5. No-op when bootstrap disabled
        props.getAdminBootstrap().setEnabled(false);
        runner.run(new DefaultApplicationArguments());
    }

    @Test
    @DisplayName(
            "login with password exceeding 72 UTF-8 bytes executes dummy BCrypt check and throws"
                    + " UNAUTHORIZED instead of 500")
    void login_whenPasswordExceeds72Utf8Bytes_shouldThrowUnauthorized() {
        String oversizedPassword = "Ẩ".repeat(30); // 30 * 3 UTF-8 bytes = 90 bytes (> 72 bytes)
        assertThatThrownBy(() -> authService.login(new LoginRequest("admin", oversizedPassword)))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(
                        ex -> {
                            BusinessRuleException bre = (BusinessRuleException) ex;
                            assertThat(bre.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                            assertThat(bre.getMessage())
                                    .isEqualTo(AuthService.INVALID_CREDENTIALS_MESSAGE);
                        });

        verify(passwordEncoder)
                .matches(eq("oversized-password"), eq(AuthService.DUMMY_BCRYPT_HASH));
    }
}
