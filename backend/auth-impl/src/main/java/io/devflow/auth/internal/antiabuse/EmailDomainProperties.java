package io.devflow.auth.internal.antiabuse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Email domain checks applied at registration. Bound to prefix {@code devflow.registration.email-domain}.
 */
@Component
@ConfigurationProperties(prefix = "devflow.registration.email-domain")
public class EmailDomainProperties {

    private boolean disposableCheckEnabled = true;

    /**
     * Domains blocked in addition to the bundled disposable-domain list.
     */
    private List<String> extraBlockedDomains = new ArrayList<>();

    private boolean mxCheckEnabled = true;

    /**
     * Per-query DNS timeout. A lookup that times out lets the registration through (fail open).
     */
    private Duration mxLookupTimeout = Duration.ofSeconds(2);

    private Duration mxCacheTtl = Duration.ofHours(1);

    public boolean isDisposableCheckEnabled() {
        return disposableCheckEnabled;
    }

    public void setDisposableCheckEnabled(boolean disposableCheckEnabled) {
        this.disposableCheckEnabled = disposableCheckEnabled;
    }

    public List<String> getExtraBlockedDomains() {
        return extraBlockedDomains;
    }

    public void setExtraBlockedDomains(List<String> extraBlockedDomains) {
        this.extraBlockedDomains = extraBlockedDomains;
    }

    public boolean isMxCheckEnabled() {
        return mxCheckEnabled;
    }

    public void setMxCheckEnabled(boolean mxCheckEnabled) {
        this.mxCheckEnabled = mxCheckEnabled;
    }

    public Duration getMxLookupTimeout() {
        return mxLookupTimeout;
    }

    public void setMxLookupTimeout(Duration mxLookupTimeout) {
        this.mxLookupTimeout = mxLookupTimeout;
    }

    public Duration getMxCacheTtl() {
        return mxCacheTtl;
    }

    public void setMxCacheTtl(Duration mxCacheTtl) {
        this.mxCacheTtl = mxCacheTtl;
    }
}
