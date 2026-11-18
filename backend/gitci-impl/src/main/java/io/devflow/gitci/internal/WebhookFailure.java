package io.devflow.gitci.internal;

final class WebhookFailure extends RuntimeException {
    final int status;
    final String code;
    WebhookFailure(int status, String code) { this.status = status; this.code = code; }
}
