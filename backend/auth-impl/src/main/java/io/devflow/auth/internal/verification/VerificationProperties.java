package io.devflow.auth.internal.verification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Email verification settings. Bound to prefix {@code devflow.verification}.
 */
@Component
@ConfigurationProperties(prefix = "devflow.verification")
public class VerificationProperties {

    /**
     * Lifetime of an emailed verification token.
     */
    private Duration tokenTtl = Duration.ofHours(24);

    /**
     * Public base URL of the web client; the verification link points to {@code <base>/verify-email?token=...}.
     */
    private String frontendBaseUrl = "http://localhost:5173";

    /**
     * Sender address for transactional email (RFC 5322 mailbox, e.g. {@code DevFlow <no-reply@example.com>}).
     */
    private String mailFrom = "DevFlow <no-reply@devflow.local>";

    /**
     * Used/expired tokens older than this are purged.
     */
    private Duration tokenRetention = Duration.ofDays(7);

    /**
     * Accounts still unverified after this period are deleted (abandoned sign-ups, data minimisation).
     */
    private Duration unverifiedAccountRetention = Duration.ofDays(7);

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public String getFrontendBaseUrl() {
        return frontendBaseUrl;
    }

    public void setFrontendBaseUrl(String frontendBaseUrl) {
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public void setMailFrom(String mailFrom) {
        this.mailFrom = mailFrom;
    }

    public Duration getTokenRetention() {
        return tokenRetention;
    }

    public void setTokenRetention(Duration tokenRetention) {
        this.tokenRetention = tokenRetention;
    }

    public Duration getUnverifiedAccountRetention() {
        return unverifiedAccountRetention;
    }

    public void setUnverifiedAccountRetention(Duration unverifiedAccountRetention) {
        this.unverifiedAccountRetention = unverifiedAccountRetention;
    }
}
