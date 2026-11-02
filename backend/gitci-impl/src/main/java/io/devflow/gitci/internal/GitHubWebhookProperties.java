package io.devflow.gitci.internal;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-managed secrets, keyed by the internal repository ID. Never log this object. */
@Component
@ConfigurationProperties(prefix = "devflow.gitci.github-webhook")
public class GitHubWebhookProperties {
    private boolean enabled;
    private int maxBodyBytes = 1048576;
    private int maxInFlight = 16;
    private Map<UUID, Binding> bindings = new LinkedHashMap<>();

    @PostConstruct
    void validate() {
        if (maxBodyBytes < 1 || maxBodyBytes > 10485760 || maxInFlight < 1 || maxInFlight > 128)
            throw new IllegalStateException("Invalid GitHub webhook limits");
        if (!enabled) return;
        if (bindings.isEmpty()) throw new IllegalStateException("GitHub webhook requires bindings");
        Set<String> refs = new HashSet<>();
        Set<String> keys = new HashSet<>();
        bindings.forEach((id, b) -> {
            if (id == null || b == null || b.reference == null || !b.reference.matches("[A-Za-z0-9:_-]{1,255}")
                    || !refs.add(b.reference) || !validKey(b.current) || !keys.add(b.current)
                    || ((b.previous == null) != (b.previousValidUntil == null))
                    || (b.previous != null && (!validKey(b.previous) || !keys.add(b.previous))))
                throw new IllegalStateException("Invalid or duplicate GitHub webhook binding");
        });
    }

    private static boolean validKey(String key) {
        return key != null && key.length() >= 32 && key.length() <= 512
                && !key.contains("\n") && !key.contains("\r") && !key.isBlank();
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxBodyBytes() { return maxBodyBytes; }
    public void setMaxBodyBytes(int value) { maxBodyBytes = value; }
    public int getMaxInFlight() { return maxInFlight; }
    public void setMaxInFlight(int value) { maxInFlight = value; }
    public Map<UUID, Binding> getBindings() { return bindings; }
    public void setBindings(Map<UUID, Binding> bindings) { this.bindings = bindings; }

    public static class Binding {
        private String reference;
        private String current;
        private String previous;
        private Instant previousValidUntil;
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
        public String getCurrent() { return current; }
        public void setCurrent(String current) { this.current = current; }
        public String getPrevious() { return previous; }
        public void setPrevious(String previous) { this.previous = previous; }
        public Instant getPreviousValidUntil() { return previousValidUntil; }
        public void setPreviousValidUntil(Instant previousValidUntil) { this.previousValidUntil = previousValidUntil; }
    }
}
