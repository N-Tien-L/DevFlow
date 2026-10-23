package io.devflow.auth.internal.exception;

/**
 * Rejects an email whose domain is disposable or cannot receive mail. Rendered as a field error on
 * {@code email} with a machine-readable {@code code}.
 */
public class EmailDomainRejectedException extends InvalidFieldException {

    private final String code;

    public EmailDomainRejectedException(String code, String message) {
        super("email", message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
