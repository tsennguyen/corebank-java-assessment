package com.example.corebanking.auth.infrastructure;

import com.example.corebanking.auth.domain.AppUser;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues and decodes signed HS256 JSON Web Tokens (JWT) using Spring Security's {@link JwtEncoder}
 * and {@link JwtDecoder} backed by Nimbus JOSE + JWT (L2-02, FR-13, ADR-007).
 */
@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final SecurityProperties securityProperties;
    private final Clock clock;

    public JwtTokenService(
            JwtEncoder jwtEncoder,
            JwtDecoder jwtDecoder,
            SecurityProperties securityProperties,
            Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.securityProperties = securityProperties;
        this.clock = clock;
    }

    /**
     * Generates a signed HS256 JWT access token for the given authenticated user.
     *
     * @param user the authenticated {@link AppUser}
     * @return the compact serialized JWT string
     */
    public String generateAccessToken(AppUser user) {
        Instant now = clock.instant();
        long ttlSeconds = securityProperties.getJwt().getAccessTokenTtlSeconds();
        Instant expiresAt = now.plusSeconds(ttlSeconds);

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();

        JwtClaimsSet.Builder claimsBuilder =
                JwtClaimsSet.builder()
                        .issuer(securityProperties.getJwt().getIssuer())
                        .subject(String.valueOf(user.getId()))
                        .issuedAt(now)
                        .expiresAt(expiresAt)
                        .id(UUID.randomUUID().toString())
                        .claim("username", user.getUsername())
                        .claim("role", user.getRole().name());

        if (user.getCustomerId() != null) {
            claimsBuilder.claim("customerId", user.getCustomerId());
        }

        return jwtEncoder
                .encode(JwtEncoderParameters.from(header, claimsBuilder.build()))
                .getTokenValue();
    }

    /** Returns the configured access token time-to-live in seconds. */
    public long getAccessTokenTtlSeconds() {
        return securityProperties.getJwt().getAccessTokenTtlSeconds();
    }

    /** Decodes and verifies a JWT string using the configured HS256 decoder. */
    public Jwt decode(String token) {
        return jwtDecoder.decode(token);
    }
}
