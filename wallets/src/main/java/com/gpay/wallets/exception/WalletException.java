package com.gpay.wallets.exception;

public class WalletException {
    public static class WalletNotFoundException extends RuntimeException {
        public WalletNotFoundException(String message) { super(message); }
    }

    public static class WalletAlreadyExistsException extends RuntimeException {
        public WalletAlreadyExistsException(String message) { super(message); }
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String message) { super(message); }
    }

    public static class InvalidTransferException extends RuntimeException {
        public InvalidTransferException(String message) { super(message); }
    }

    public static class UnauthorizedAccessException extends RuntimeException {
        public UnauthorizedAccessException(String message) { super(message); }
    }
}
