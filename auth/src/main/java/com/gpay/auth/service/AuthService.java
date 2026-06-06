package com.gpay.auth.service;

import com.gpay.auth.dto.AuthDTO.*;

public interface AuthService {
    RegisterResponse register(RegisterRequest request);
    TokenResponse login(LoginRequest request);
    TokenResponse refreshToken(RefreshTokenRequest request);
    void logout(String refreshTokenStr);
}
