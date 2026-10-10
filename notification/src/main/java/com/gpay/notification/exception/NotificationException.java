package com.gpay.notification.exception;

public class NotificationException {
    public static class NotificationNotFoundException extends RuntimeException {
        public NotificationNotFoundException(String message) { super(message); }
    }

    /** Event is structurally valid JSON but semantically unusable; redelivery cannot fix it. */
    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(String message) { super(message); }
    }
}
