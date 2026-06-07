package com.gpay.payment.exception;

public class PaymentException {
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) { super(message); }
    }

    public static class DailyLimitExceededException extends RuntimeException {
        public DailyLimitExceededException(String message) { super(message); }
    }

    public static class InsufficientBalanceException extends RuntimeException {
        public InsufficientBalanceException(String message) { super(message); }
    }

    public static class InvalidTransferException extends RuntimeException {
        public InvalidTransferException(String message) { super(message); }
    }

    public static class TransferFailedException extends RuntimeException {
        public TransferFailedException(String message) { super(message); }
    }

    public static class DuplicateRequestException extends RuntimeException {
        private final Object cachedResponse;
        public DuplicateRequestException(String message, Object cachedResponse) {
            super(message);
            this.cachedResponse = cachedResponse;
        }
        public Object getCachedResponse() { return cachedResponse; }
    }

    public static class WebhookException extends RuntimeException {
        public WebhookException(String message) { super(message); }
    }

    public static class WebhookSignatureException extends RuntimeException {
        public WebhookSignatureException(String message) { super(message); }
    }

    public static class TransactionNotFoundException extends RuntimeException {
        public TransactionNotFoundException(String message) { super(message); }
    }
}
