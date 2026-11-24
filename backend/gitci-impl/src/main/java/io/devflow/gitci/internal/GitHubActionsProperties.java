package io.devflow.gitci.internal;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("devflow.gitci.ci")
class GitHubActionsProperties {
    private boolean fetchEnabled;
    private boolean publishEnabled;
    private boolean retentionEnabled;
    private int repositoryBacklog = 100;
    private int globalBacklog = 5000;
    private int slots = 2;
    private int connectSeconds = 3;
    private int readSeconds = 10;
    private int budgetSeconds = 30;
    private int leaseSeconds = 60;
    private int retentionDays = 7;
    private int maxArchiveBytes = 8 * 1024 * 1024;
    private int maxExpandedBytes = 32 * 1024 * 1024;
    private int maxEntryBytes = 2 * 1024 * 1024;
    private int maxEntries = 200;
    private int maxJobPages = 5;
    private int maxArchiveHops = 3;
    private Set<String> archiveHosts = Set.of("results-receiver.actions.githubusercontent.com");
    private Map<UUID, Credential> credentials = new LinkedHashMap<>();

    @PostConstruct
    void validate() {
        if (repositoryBacklog < 1 || repositoryBacklog > globalBacklog || globalBacklog > 50_000
                || slots < 1 || slots > 8 || connectSeconds < 1 || connectSeconds > 10
                || readSeconds < connectSeconds || readSeconds > 30 || budgetSeconds < readSeconds || budgetSeconds > 60
                || leaseSeconds <= budgetSeconds + 10 || leaseSeconds > 300 || retentionDays < 1 || retentionDays > 30
                || maxArchiveBytes < 1024 || maxArchiveBytes > 8 * 1024 * 1024
                || maxExpandedBytes < 1024 || maxExpandedBytes > 32 * 1024 * 1024
                || maxEntryBytes < 1024 || maxEntryBytes > Math.min(maxExpandedBytes, 2 * 1024 * 1024)
                || maxEntries < 1 || maxEntries > 200 || maxJobPages < 1 || maxJobPages > 5
                || maxArchiveHops < 1 || maxArchiveHops > 3
                || archiveHosts.isEmpty() || archiveHosts.size() > 50 || archiveHosts.stream().anyMatch(host ->
                    !host.matches("[a-z0-9][a-z0-9.-]{1,250}[a-z0-9]") || host.contains("..")
                    || !(host.endsWith(".actions.githubusercontent.com") || host.endsWith(".blob.core.windows.net")))) {
            throw new IllegalStateException("Invalid CI worker configuration");
        }
        credentials.forEach((id, credential) -> {
            if (id == null || credential == null || credential.repositoryId == null
                    || !credential.repositoryId.matches("[1-9][0-9]{0,99}")
                    || credential.token == null || !credential.token.matches("[A-Za-z0-9_-]{20,512}"))
                throw new IllegalStateException("Invalid CI credential binding");
        });
    }

    public boolean isFetchEnabled() { return fetchEnabled; }
    public void setFetchEnabled(boolean value) { fetchEnabled = value; }
    public boolean isPublishEnabled() { return publishEnabled; }
    public void setPublishEnabled(boolean value) { publishEnabled = value; }
    public boolean isRetentionEnabled() { return retentionEnabled; }
    public void setRetentionEnabled(boolean value) { retentionEnabled = value; }
    public int getRepositoryBacklog() { return repositoryBacklog; }
    public void setRepositoryBacklog(int value) { repositoryBacklog = value; }
    public int getGlobalBacklog() { return globalBacklog; }
    public void setGlobalBacklog(int value) { globalBacklog = value; }
    public int getSlots() { return slots; }
    public void setSlots(int value) { slots = value; }
    public int getConnectSeconds() { return connectSeconds; }
    public void setConnectSeconds(int value) { connectSeconds = value; }
    public int getReadSeconds() { return readSeconds; }
    public void setReadSeconds(int value) { readSeconds = value; }
    public int getBudgetSeconds() { return budgetSeconds; }
    public void setBudgetSeconds(int value) { budgetSeconds = value; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) { leaseSeconds = value; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { retentionDays = value; }
    public int getMaxArchiveBytes() { return maxArchiveBytes; }
    public void setMaxArchiveBytes(int value) { maxArchiveBytes = value; }
    public int getMaxExpandedBytes() { return maxExpandedBytes; }
    public void setMaxExpandedBytes(int value) { maxExpandedBytes = value; }
    public int getMaxEntryBytes() { return maxEntryBytes; }
    public void setMaxEntryBytes(int value) { maxEntryBytes = value; }
    public int getMaxEntries() { return maxEntries; }
    public void setMaxEntries(int value) { maxEntries = value; }
    public int getMaxJobPages() { return maxJobPages; }
    public void setMaxJobPages(int value) { maxJobPages = value; }
    public int getMaxArchiveHops() { return maxArchiveHops; }
    public void setMaxArchiveHops(int value) { maxArchiveHops = value; }
    public Set<String> getArchiveHosts() { return archiveHosts; }
    public void setArchiveHosts(Set<String> value) { archiveHosts = Set.copyOf(value); }
    public Map<UUID, Credential> getCredentials() { return credentials; }
    public void setCredentials(Map<UUID, Credential> value) { credentials = value; }

    Duration budget() { return Duration.ofSeconds(budgetSeconds); }

    String token(UUID repository, String remoteId) {
        Credential credential = credentials.get(repository);
        return credential != null && remoteId.equals(credential.repositoryId) ? credential.token : null;
    }

    public static final class Credential {
        private String repositoryId;
        private String token;
        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String value) { repositoryId = value; }
        public String getToken() { return token; }
        public void setToken(String value) { token = value; }
    }
}
