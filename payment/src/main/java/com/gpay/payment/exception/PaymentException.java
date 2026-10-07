package com.gpay.payment.exception;

public class PaymentException {
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) { super(message); }
    }

    public static class DailyLimitExceededException extends RuntimeException {
        public DailyLimitExceededException(String message) { super(message); }
    }

    public static class InvalidTransferException extends RuntimeException {
        public InvalidTransferException(String message) { super(message); }
    }

    public static class InvalidIdempotencyKeyException extends RuntimeException {
        public InvalidIdempotencyKeyException(String message) { super(message); }
    }

    /** Same idempotency key re-sent with a different request (other type or amount). */
    public static class IdempotencyKeyReusedException extends RuntimeException {
        public IdempotencyKeyReusedException(String message) { super(message); }
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
