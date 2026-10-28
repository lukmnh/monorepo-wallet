package com.gpay.auth.service.Impl;

import com.gpay.auth.client.WalletClient;
import com.gpay.auth.dto.AuthDTO.*;
import com.gpay.auth.entity.RefreshToken;
import com.gpay.auth.entity.Users;
import com.gpay.auth.exception.AuthException;
import com.gpay.auth.repository.RefreshTokenRepository;
import com.gpay.auth.repository.UserRepository;
import com.gpay.auth.service.common.JwtService;
import com.gpay.auth.service.common.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String DUMMY_HASH = "dummy-hash";
    private static final String IP = "10.0.0.1";

    @Mock UserRepository userRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock LoginAttemptService loginAttemptService;
    @Mock WalletClient walletClient;

    AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        // Constructor pre-computes the timing-equalizer hash used for unknown usernames
        when(passwordEncoder.encode("timing-equalizer")).thenReturn(DUMMY_HASH);
        service = new AuthServiceImpl(userRepository, refreshTokenRepository, passwordEncoder,
                jwtService, loginAttemptService, walletClient);
    }

    private static Users user(boolean active) {
        return Users.builder().id(UUID.randomUUID()).username("alice").email("alice@example.com")
                .password("stored-hash").active(active).build();
    }

    private void stubTokenIssuing() {
        when(jwtService.generateAccessToken(any(), anyString())).thenReturn("access-token");
        when(jwtService.getRefreshExpiryDays()).thenReturn(7L);
        when(jwtService.getAccessExpirySeconds()).thenReturn(900L);
    }

    @Nested
    class Register {
        @Test
        void normalizesIdentitySavesUserAndProvisionsWallet() {
            when(passwordEncoder.encode("password123")).thenReturn("bcrypt");
            when(userRepository.saveAndFlush(any())).thenAnswer(inv -> {
                Users u = inv.getArgument(0);
                u.setId(UUID.randomUUID());
                return u;
            });

            RegisterResponse res = service.register(new RegisterRequest("  Alice ", "Alice@Example.COM", "password123"));

            ArgumentCaptor<Users> saved = ArgumentCaptor.forClass(Users.class);
            verify(userRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getUsername()).isEqualTo("alice");
            assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
            assertThat(saved.getValue().getPassword()).isEqualTo("bcrypt");
            verify(walletClient).createWallet(saved.getValue().getId());
            assertThat(res.username()).isEqualTo("alice");
        }

        @Test
        void rejectsDuplicateUsername() {
            when(userRepository.existsByUsername("alice")).thenReturn(true);

            assertThatThrownBy(() -> service.register(new RegisterRequest("alice", "a@x.com", "password123")))
                    .isInstanceOf(AuthException.UsernameAlreadyExistsException.class);
            verifyNoInteractions(walletClient);
        }

        @Test
        void rejectsDuplicateEmail() {
            when(userRepository.existsByEmail("a@x.com")).thenReturn(true);

            assertThatThrownBy(() -> service.register(new RegisterRequest("alice", "a@x.com", "password123")))
                    .isInstanceOf(AuthException.EmailAlreadyExistsException.class);
        }

        @Test
        void rejectsPasswordAboveBcryptByteLimit() {
            String multiByte = "é".repeat(37);   // 37 chars, 74 bytes in UTF-8

            assertThatThrownBy(() -> service.register(new RegisterRequest("alice", "a@x.com", multiByte)))
                    .isInstanceOf(AuthException.InvalidPasswordException.class);
            verifyNoInteractions(userRepository);
        }

        @Test
        void walletFailurePropagatesSoTheUserRowRollsBack() {
            when(passwordEncoder.encode("password123")).thenReturn("bcrypt");
            when(userRepository.saveAndFlush(any())).thenAnswer(inv -> {
                Users u = inv.getArgument(0);
                u.setId(UUID.randomUUID());
                return u;
            });
            doThrow(new AuthException.WalletProvisioningException("down")).when(walletClient).createWallet(any());

            assertThatThrownBy(() -> service.register(new RegisterRequest("alice", "a@x.com", "password123")))
                    .isInstanceOf(AuthException.WalletProvisioningException.class);
        }
    }

    @Nested
    class Login {
        @Test
        void successIssuesTokenPairAndResetsUserCounter() {
            Users u = user(true);
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
            when(passwordEncoder.matches("password123", "stored-hash")).thenReturn(true);
            stubTokenIssuing();

            TokenResponse res = service.login(new LoginRequest("ALICE", "password123"), IP);

            assertThat(res.accessToken()).isEqualTo("access-token");
            assertThat(res.refreshToken()).isNotBlank();
            assertThat(res.tokenType()).isEqualTo("Bearer");
            verify(loginAttemptService).assertNotBlocked("alice", IP);
            verify(loginAttemptService).recordSuccess("alice");
            verify(refreshTokenRepository).save(argThat(t -> !t.getTokenHash().equals(res.refreshToken())));
        }

        @Test
        void wrongPasswordRecordsFailure() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(true)));
            when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

            assertThatThrownBy(() -> service.login(new LoginRequest("alice", "wrong"), IP))
                    .isInstanceOf(AuthException.InvalidCredentialsException.class);
            verify(loginAttemptService).recordFailure("alice", IP);
            verify(loginAttemptService, never()).recordSuccess(any());
        }

        @Test
        void unknownUserStillRunsBcryptAgainstDummyHash() {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(new LoginRequest("ghost", "password123"), IP))
                    .isInstanceOf(AuthException.InvalidCredentialsException.class);
            verify(passwordEncoder).matches("password123", DUMMY_HASH);
            verify(loginAttemptService).recordFailure("ghost", IP);
        }

        @Test
        void inactiveAccountIsRevealedOnlyAfterCorrectPassword() {
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user(false)));
            when(passwordEncoder.matches("password123", "stored-hash")).thenReturn(true);

            assertThatThrownBy(() -> service.login(new LoginRequest("alice", "password123"), IP))
                    .isInstanceOf(AuthException.AccountInactiveException.class);
            verifyNoInteractions(jwtService);
        }

        @Test
        void lockedOutUserIsRejectedBeforePasswordCheck() {
            doThrow(new AuthException.TooManyLoginAttemptsException("locked", 600))
                    .when(loginAttemptService).assertNotBlocked("alice", IP);

            assertThatThrownBy(() -> service.login(new LoginRequest("alice", "password123"), IP))
                    .isInstanceOf(AuthException.TooManyLoginAttemptsException.class);
            verify(passwordEncoder, never()).matches(any(), any());
        }
    }

    @Nested
    class Refresh {
        @Test
        void rotatesTokenWhenCompareAndSetWins() {
            Users u = user(true);
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(RefreshToken.builder().user(u).tokenHash("h").build()));
            when(refreshTokenRepository.consumeIfActive(anyString(), any())).thenReturn(1);
            stubTokenIssuing();

            TokenResponse res = service.refreshToken(new RefreshTokenRequest("old-token"));

            assertThat(res.refreshToken()).isNotEqualTo("old-token");
            verify(refreshTokenRepository).save(any(RefreshToken.class));
        }

        @Test
        void reuseOfRotatedTokenRevokesAllSessions() {
            Users u = user(true);
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(RefreshToken.builder().user(u).tokenHash("h").revoked(true).build()));
            when(refreshTokenRepository.consumeIfActive(anyString(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.refreshToken(new RefreshTokenRequest("stolen")))
                    .isInstanceOf(AuthException.InvalidTokenException.class);
            verify(refreshTokenRepository).revokeAllByUserId(u.getId());
        }

        @Test
        void expiredButNotRevokedTokenDoesNotRevokeOtherSessions() {
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(RefreshToken.builder().user(user(true)).tokenHash("h").build()));
            when(refreshTokenRepository.consumeIfActive(anyString(), any())).thenReturn(0);

            assertThatThrownBy(() -> service.refreshToken(new RefreshTokenRequest("expired")))
                    .isInstanceOf(AuthException.InvalidTokenException.class);
            verify(refreshTokenRepository, never()).revokeAllByUserId(any());
        }

        @Test
        void unknownTokenIsRejected() {
            when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.refreshToken(new RefreshTokenRequest("nope")))
                    .isInstanceOf(AuthException.InvalidTokenException.class);
        }

        @Test
        void inactiveUserCannotRefresh() {
            Users u = user(false);
            when(refreshTokenRepository.findByTokenHash(anyString()))
                    .thenReturn(Optional.of(RefreshToken.builder().user(u).tokenHash("h").build()));
            when(refreshTokenRepository.consumeIfActive(anyString(), any())).thenReturn(1);

            assertThatThrownBy(() -> service.refreshToken(new RefreshTokenRequest("t")))
                    .isInstanceOf(AuthException.InvalidTokenException.class);
            verify(refreshTokenRepository).revokeAllByUserId(u.getId());
            verifyNoInteractions(jwtService);
        }
    }

    @Test
    void logoutRevokesTheToken() {
        RefreshToken token = RefreshToken.builder().user(user(true)).tokenHash("h").build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        service.logout("t");

        assertThat(token.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(token);
    }
}
