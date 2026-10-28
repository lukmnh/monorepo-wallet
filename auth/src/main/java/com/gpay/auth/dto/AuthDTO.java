package com.gpay.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AuthDTO {
    public record RegisterRequest(
            @NotBlank(message = "Username is required")
            @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
            @Pattern(
                    regexp = "^(?!\\d+$)[a-zA-Z0-9]+$",
                    message = "Username must be alphanumeric and cannot be only numbers"
            )
            String username,

            @NotBlank(message = "Email is required")
            @Email(message = "Invalid email format")
            @Size(max = 100, message = "Email must be at most 100 characters")
            String email,

            @NotBlank(message = "Password is required")
            // BCrypt only accepts up to 72 bytes; longer input makes the encoder throw
            @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
            String password
    ) {}

    public record LoginRequest(
            @NotBlank(message = "Username is required")
            String username,

            @NotBlank(message = "Password is required")
            String password
    ) {}

    public record RefreshTokenRequest(
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {}

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String userId,
            long accessExpiresIn,
            String tokenType
    ) {}

    public record RegisterResponse(
            String userId,
            String username,
            String email
    ) {}

    public record ApiResponse<T>(
            boolean success,
            String message,
            T data
    ) {
        public static <T> ApiResponse<T> ok(String message, T data) {
            return new ApiResponse<>(true, message, data);
        }
        public static <T> ApiResponse<T> error(String message) {
            return new ApiResponse<>(false, message, null);
        }
    }
}
