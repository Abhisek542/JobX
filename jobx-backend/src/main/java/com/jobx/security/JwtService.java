package com.jobx.security;

import com.jobx.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    // Keep in sync with the fallback in application.yml — its only job is to
    // let dev machines run without an env var. Outside the dev profile it is a
    // startup error, never a silent default.
    static final String DEV_ONLY_SECRET = "dev-only-insecure-secret-change-me-please-32bytes";

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${jobx.jwt.secret}") String secret,
                       @Value("${jobx.jwt.expiration-ms}") long expirationMs,
                       Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("dev"))
                && (secret == null || secret.isBlank() || DEV_ONLY_SECRET.equals(secret))) {
            throw new IllegalStateException(
                    "JOBX_JWT_SECRET is missing or still the dev default — refusing to start outside the 'dev' profile");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    /** Throws JwtException (expired, bad signature, malformed) — callers treat that as "unauthenticated". */
    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
