package com.gpay.auth.service.Impl;

import com.gpay.auth.dto.AuthDTO.*;
import com.gpay.auth.entity.RefreshToken;
import com.gpay.auth.entity.Users;
import com.gpay.auth.exception.AuthException;
import com.gpay.auth.repository.RefreshTokenRepository;
import com.gpay.auth.repository.UserRepository;
import com.gpay.auth.service.AuthService;
import com.gpay.auth.service.common.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;
    // BCrypt rejects input above 72 bytes (UTF-8) in both encode() and matches()
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    // Compared against when the username does not exist, so both paths cost one BCrypt check
    private final String dummyPasswordHash;

    public AuthServiceImpl(UserRepository userRepository,
                           RefreshTokenRepository refreshTokenRepository,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer");
    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // @Size counts chars; multi-byte input can still exceed BCrypt's byte limit
        if (exceedsBcryptLimit(request.password())) {
            throw new AuthException.InvalidPasswordException("Password must be at most 72 bytes");
        }

        String username = normalize(request.username());
        String email = normalize(request.email());

        // Fast-path checks; a concurrent duplicate still hits the DB unique index -> 409 via GlobalExceptionHandler
        if (userRepository.existsByUsername(username)) {
            throw new AuthException.UsernameAlreadyExistsException("Username already taken");
        }
        if (userRepository.existsByEmail(email)) {
            throw new AuthException.EmailAlreadyExistsException("Email already registered");
        }

        Users user = Users.builder()
                .username(username)
                .email(email)
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
        // Such a password can never match a stored hash; reject before BCrypt throws
        if (exceedsBcryptLimit(request.password())) {
            throw new AuthException.InvalidCredentialsException("Invalid username or password");
        }

        Users user = userRepository.findByUsername(normalize(request.username())).orElse(null);

        // Always run BCrypt: response time must not reveal whether the username exists
        boolean passwordValid = passwordEncoder.matches(
                request.password(), user != null ? user.getPassword() : dummyPasswordHash);
        if (user == null || !passwordValid) {
            throw new AuthException.InvalidCredentialsException("Invalid username or password");
        }

        // Account status is only revealed to someone who already proved the password
        if (!user.isActive()) {
            throw new AuthException.AccountInactiveException("Account is disabled");
        }

        MDC.put("userId", user.getId().toString());

        return generateTokenPair(user);
    }

    @Override
    @Transactional(noRollbackFor = AuthException.InvalidTokenException.class)
    public TokenResponse refreshToken(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.refreshToken());

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException.InvalidTokenException("Invalid refresh token"));

        // Atomic compare-and-set: of N concurrent refreshes with the same token, exactly one wins
        if (refreshTokenRepository.consumeIfActive(tokenHash, LocalDateTime.now()) == 0) {
            if (refreshToken.isRevoked()) {
                // A rotated token was presented again => likely stolen; kill every session of this user.
                // noRollbackFor keeps this revocation committed even though we throw.
                refreshTokenRepository.revokeAllByUserId(refreshToken.getUser().getId());
                log.warn("Refresh token reuse detected, all sessions revoked userId={}", refreshToken.getUser().getId());
            }
            throw new AuthException.InvalidTokenException("Invalid refresh token");
        }

        Users user = refreshToken.getUser();
        if (!user.isActive()) {
            refreshTokenRepository.revokeAllByUserId(user.getId());
            throw new AuthException.InvalidTokenException("Invalid refresh token");
        }

        return generateTokenPair(user);
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
        String refreshTokenStr = generateOpaqueToken();

        RefreshToken refreshTokenEntity = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(refreshTokenStr))
                .expiresAt(LocalDateTime.now().plusDays(jwtService.getRefreshExpiryDays()))
                .build();
        refreshTokenRepository.save(refreshTokenEntity);

        return new TokenResponse(
                accessToken,
                refreshTokenStr,
                String.valueOf(user.getId()),
                jwtService.getAccessExpirySeconds(),
                "Bearer"
        );
    }

    // Refresh tokens are validated by DB lookup only, so a random 256-bit value is enough (no JWT needed)
    private static String generateOpaqueToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
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
