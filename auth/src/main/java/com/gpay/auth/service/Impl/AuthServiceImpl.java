package com.gpay.auth.service.Impl;

import com.gpay.auth.dto.AuthDTO.*;
import com.gpay.auth.entity.RefreshToken;
import com.gpay.auth.entity.Users;
import com.gpay.auth.exception.AuthException;
import com.gpay.auth.repository.RefreshTokenRepository;
import com.gpay.auth.repository.UserRepository;
import com.gpay.auth.service.AuthService;
import com.gpay.auth.service.common.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new AuthException.UsernameAlreadyExistsException("Username already taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new AuthException.EmailAlreadyExistsException("Email already registered");
        }

        Users user = Users.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .build();

        user = userRepository.save(user);
        log.info("User registered: username={}", user.getUsername());

        return new RegisterResponse(
                user.getId().toString(),
                user.getUsername(),
                user.getEmail()
        );
    }

    @Override
    @Transactional
    public TokenResponse login(LoginRequest request) {
        Users user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new AuthException.InvalidCredentialsException("Invalid username or password"));

        if (!user.isActive()) {
            throw new AuthException.AccountInactiveException("Account is disabled");
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new AuthException.InvalidCredentialsException("Invalid username or password");
        }

        MDC.put("userId", user.getId().toString());

        return generateTokenPair(user);
    }

    @Override
    @Transactional
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.refreshToken());

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException.InvalidTokenException("Invalid refresh token"));

        if (refreshToken.isRevoked()) {
            throw new AuthException.InvalidTokenException("Refresh token has been revoked");
        }

        if (refreshToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new AuthException.InvalidTokenException("Refresh token has expired");
        }

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        return generateTokenPair(refreshToken.getUser());
    }

    @Override
    @Transactional
    public void logout(String refreshTokenStr) {
        String tokenHash = hashToken(refreshTokenStr);
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    private TokenResponse generateTokenPair(Users user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername());
        String refreshTokenStr = jwtService.generateRefreshToken(user.getId());

        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(refreshTokenStr))
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        refreshTokenRepository.save(refreshTokenEntity);

        return new TokenResponse(
                accessToken,
                refreshTokenStr,
                jwtService.getAccessExpirySeconds(),
                "Bearer"
        );
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
