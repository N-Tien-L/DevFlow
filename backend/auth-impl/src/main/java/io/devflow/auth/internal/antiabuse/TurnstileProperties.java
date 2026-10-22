package io.devflow.auth.internal.antiabuse;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cloudflare Turnstile settings for public auth forms. Bound to prefix {@code devflow.turnstile}.
 */
@Component
@ConfigurationProperties(prefix = "devflow.turnstile")
public class TurnstileProperties {

    /**
     * Disabled by default so local runs and tests need no Cloudflare account; production must enable it.
     */
    private boolean enabled = false;

    /**
     * Private Siteverify secret. Required when {@link #enabled}; never sent to the browser.
     */
    private String secretKey = "";

    private String siteverifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(3);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getSiteverifyUrl() {
        return siteverifyUrl;
    }

    public void setSiteverifyUrl(String siteverifyUrl) {
        this.siteverifyUrl = siteverifyUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
