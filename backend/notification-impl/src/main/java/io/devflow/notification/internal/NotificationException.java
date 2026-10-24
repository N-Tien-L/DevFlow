package io.devflow.notification.internal;

public class NotificationException extends RuntimeException {
    private final int status;
    private final Long retryAfter;
    public NotificationException(int status, String message) { this(status, message, null); }
    public NotificationException(int status, String message, Long retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }
    public int status() { return status; }
    public Long retryAfter() { return retryAfter; }
}
