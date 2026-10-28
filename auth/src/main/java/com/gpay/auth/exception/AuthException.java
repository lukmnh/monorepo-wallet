package com.gpay.auth.exception;

public class AuthException {
    public static class UsernameAlreadyExistsException extends RuntimeException {
        public UsernameAlreadyExistsException(String message) { super(message); }
    }

    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String message) { super(message); }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException(String message) { super(message); }
    }

    public static class AccountInactiveException extends RuntimeException {
        public AccountInactiveException(String message) { super(message); }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
    }

    public static class InvalidPasswordException extends RuntimeException {
        public InvalidPasswordException(String message) { super(message); }
    }

    public static class TooManyLoginAttemptsException extends RuntimeException {
        private final long retryAfterSeconds;
        public TooManyLoginAttemptsException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }
        public long getRetryAfterSeconds() { return retryAfterSeconds; }
    }

    public static class WalletProvisioningException extends RuntimeException {
        public WalletProvisioningException(String message) { super(message); }
    }
}
