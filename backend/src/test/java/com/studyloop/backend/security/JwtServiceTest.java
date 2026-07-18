package com.studyloop.backend.security;

import com.studyloop.backend.auth.User;
import com.studyloop.backend.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234";

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(
                new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(7)));
        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("niloy@example.com");
        user.setDisplayName("Niloy");
    }

    @Test
    void accessTokenRoundTripCarriesAllClaims() {
        String token = jwtService.generateAccessToken(user);

        Claims claims = jwtService.parse(token);

        assertEquals(user.getId().toString(), claims.getSubject());
        assertEquals("niloy@example.com", claims.get("email", String.class));
        assertEquals("USER", claims.get("role", String.class));
        assertEquals(JwtService.TYPE_ACCESS, claims.get(JwtService.CLAIM_TYPE, String.class));
    }

    @Test
    void refreshTokenCarriesRefreshType() {
        Claims claims = jwtService.parse(jwtService.generateRefreshToken(user));

        assertEquals(JwtService.TYPE_REFRESH, claims.get(JwtService.CLAIM_TYPE, String.class));
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.generateAccessToken(user);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThrows(JwtException.class, () -> jwtService.parse(tampered));
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        JwtService other = new JwtService(new JwtProperties(
                "another-secret-another-secret-another-42",
                Duration.ofMinutes(15), Duration.ofDays(7)));

        String foreign = other.generateAccessToken(user);

        assertThrows(JwtException.class, () -> jwtService.parse(foreign));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService expiredIssuer = new JwtService(
                new JwtProperties(SECRET, Duration.ofMinutes(-5), Duration.ofDays(7)));

        String token = expiredIssuer.generateAccessToken(user);

        assertThrows(ExpiredJwtException.class, () -> jwtService.parse(token));
    }

    @Test
    void secretUnder256BitsIsRejectedAtConstruction() {
        assertThrows(WeakKeyException.class, () -> new JwtService(
                new JwtProperties("too-short", Duration.ofMinutes(15), Duration.ofDays(7))));
    }
}
