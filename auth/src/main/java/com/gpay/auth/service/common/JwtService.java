package com.gpay.auth.service.common;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
@Slf4j
public class JwtService {
    private final SecretKey secretKey;
    private final long accessExpiryMinutes;
    private final long refreshExpiryDays;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-expiry-minutes}") long accessExpiryMinutes,
            @Value("${jwt.refresh-expiry-days}") long refreshExpiryDays) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessExpiryMinutes = accessExpiryMinutes;
        this.refreshExpiryDays = refreshExpiryDays;
    }

    public String generateAccessToken(UUID userId, String username) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("type", "ACCESS")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiryMinutes * 60 * 1000))
                .signWith(secretKey)
                .compact();
    }

    public String generateRefreshToken(UUID userId) {
        return Jwts.builder()
                .subject(userId.toString())
                .claim("type", "REFRESH")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpiryDays * 24 * 60 * 60 * 1000))
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
    
    public long getAccessExpirySeconds() {
        return accessExpiryMinutes * 60;
    }
}
