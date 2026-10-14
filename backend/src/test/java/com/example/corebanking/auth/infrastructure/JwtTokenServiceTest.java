package com.example.corebanking.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.corebanking.auth.domain.AppUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.util.ReflectionTestUtils;

class JwtTokenServiceTest {

    private SecurityConfig securityConfig;
    private SecurityProperties securityProperties;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig();
        securityProperties = new SecurityProperties();
        securityProperties
                .getJwt()
                .setSecret("unit-test-jwt-secret-key-at-least-256-bits-long-2026");
        securityProperties.getJwt().setAccessTokenTtlSeconds(900L);
        securityProperties.getJwt().setIssuer("corebank-java-assessment");

        JwtEncoder encoder = securityConfig.jwtEncoder(securityProperties);
        JwtDecoder decoder = securityConfig.jwtDecoder(securityProperties);
        Clock clock = Clock.systemUTC();

        jwtTokenService = new JwtTokenService(encoder, decoder, securityProperties, clock);
    }

    @Test
    @DisplayName(
            "generateAccessToken issues signed HS256 JWT with sub, username, role, customerId, jti,"
                    + " and issuer claims")
    void generateAccessToken_forCustomer_shouldContainAllRequiredClaims() {
        AppUser customer = AppUser.createCustomer("cust.an", "$2a$10$hash", 101L);
        ReflectionTestUtils.setField(customer, "id", 7L);

        String token = jwtTokenService.generateAccessToken(customer);
        assertThat(token).isNotBlank();

        Jwt decoded = jwtTokenService.decode(token);
        assertThat(decoded.getSubject()).isEqualTo("7");
        assertThat(decoded.getClaimAsString("iss")).isEqualTo("corebank-java-assessment");
        assertThat(decoded.getClaimAsString("username")).isEqualTo("cust.an");
        assertThat(decoded.getClaimAsString("role")).isEqualTo("CUSTOMER");
        assertThat(decoded.getClaimAsString("customerId")).isEqualTo("101");
        assertThat(decoded.getId()).isNotBlank();
        assertThat(decoded.getExpiresAt()).isAfter(decoded.getIssuedAt());
    }

    @Test
    @DisplayName("jwtAuthenticationConverter maps role claim to ROLE_<ROLE> authority")
    void jwtAuthenticationConverter_shouldMapRoleClaimToGrantedAuthority() {
        AppUser admin = AppUser.createAdmin("admin", "$2a$10$hash");
        ReflectionTestUtils.setField(admin, "id", 1L);

        String token = jwtTokenService.generateAccessToken(admin);
        Jwt decoded = jwtTokenService.decode(token);

        JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();
        AbstractAuthenticationToken auth = converter.convert(decoded);

        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("admin");
        assertThat(auth.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");

        Jwt jwtWithoutRole =
                Jwt.withTokenValue("dummy")
                        .header("alg", "HS256")
                        .claim("sub", "1")
                        .claim("username", "nobody")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
        AbstractAuthenticationToken authWithoutRole = converter.convert(jwtWithoutRole);
        Collection<GrantedAuthority> authorities = authWithoutRole.getAuthorities();
        assertThat(authorities).isEmpty();
    }

    @Test
    @DisplayName("buildHmacSecretKey rejects secrets shorter than 32 bytes (256 bits)")
    void buildHmacSecretKey_whenTooShort_shouldThrowIllegalArgumentException() {
        assertThatThrownBy(() -> SecurityConfig.buildHmacSecretKey("too-short-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bits");
        assertThatThrownBy(() -> SecurityConfig.buildHmacSecretKey(null))
                .isInstanceOf(IllegalArgumentException.class);

        Clock fixedPastClock = Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC);
        JwtTokenService expiredService =
                new JwtTokenService(
                        securityConfig.jwtEncoder(securityProperties),
                        securityConfig.jwtDecoder(securityProperties),
                        securityProperties,
                        fixedPastClock);
        AppUser admin = AppUser.createAdmin("admin", "$2a$10$hash");
        ReflectionTestUtils.setField(admin, "id", 1L);
        String expiredToken = expiredService.generateAccessToken(admin);

        assertThatThrownBy(() -> jwtTokenService.decode(expiredToken))
                .isInstanceOf(Exception.class);
    }
}
