package com.gpay.auth.service.common;

import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Date;
import java.util.UUID;

/**
 * Issues RS256 access tokens. Only auth-service holds the private key; wallet/payment
 * verify with the public key, so a compromised resource service cannot mint tokens.
 */
@Service
@Slf4j
public class JwtService {
    private final RSAPrivateCrtKey privateKey;
    private final String issuer;
    private final long accessExpiryMinutes;
    private final long refreshExpiryDays;

    public JwtService(
            @Value("${jwt.private-key-path}") String privateKeyPath,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.access-expiry-minutes}") long accessExpiryMinutes,
            @Value("${jwt.refresh-expiry-days}") long refreshExpiryDays) {
        this.privateKey = PemKeys.readRsaPrivateKey(Path.of(privateKeyPath));
        this.issuer = issuer;
        this.accessExpiryMinutes = accessExpiryMinutes;
        this.refreshExpiryDays = refreshExpiryDays;
        log.info("JWT signing key loaded: RS256, {} bits", privateKey.getModulus().bitLength());
    }

    public String generateAccessToken(UUID userId, String username) {
        return Jwts.builder()
                .issuer(issuer)
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim("username", username)
                .claim("type", "ACCESS")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpiryMinutes * 60 * 1000))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public long getAccessExpirySeconds() {
        return accessExpiryMinutes * 60;
    }

    public long getRefreshExpiryDays() {
        return refreshExpiryDays;
    }
}
