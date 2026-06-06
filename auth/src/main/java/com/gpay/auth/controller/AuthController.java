package com.gpay.auth.controller;

import com.gpay.auth.dto.AuthDTO;
import com.gpay.auth.dto.AuthDTO.ApiResponse;
import com.gpay.auth.dto.AuthDTO.RegisterResponse;
import com.gpay.auth.dto.AuthDTO.TokenResponse;
import com.gpay.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody AuthDTO.RegisterRequest request) {
        log.info("Register attempt for username={}", request.username());
        RegisterResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Registration successful", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody AuthDTO.LoginRequest request) {
        log.info("Login attempt for username={}", request.username());
        TokenResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.ok("Login successful", response));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody AuthDTO.RefreshTokenRequest request) {
        TokenResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", response));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody AuthDTO.RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok("Logged out successfully", null));
    }
}
