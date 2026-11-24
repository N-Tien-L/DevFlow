package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubActionsPropertiesTest {

    @Test
    void acceptsDefaultConfigurationAndValidBounds() {
        var properties = new GitHubActionsProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();

        var repoId = UUID.randomUUID();
        var credential = credential("1442558277", "github_pat_0123456789abcdefghij");
        properties.setCredentials(Map.of(repoId, credential));
        properties.setArchiveHosts(Set.of(
                "results-receiver.actions.githubusercontent.com",
                "pipelinesghubeus1.blob.core.windows.net"));
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.token(repoId, "1442558277")).isEqualTo("github_pat_0123456789abcdefghij");
        assertThat(properties.token(repoId, "9999999999")).isNull();
        assertThat(properties.token(UUID.randomUUID(), "1442558277")).isNull();
    }

    @Test
    void rejectsOutOfBoundsNumericThresholds() {
        assertInvalid(p -> p.setRepositoryBacklog(0));
        assertInvalid(p -> { p.setGlobalBacklog(10); p.setRepositoryBacklog(11); });
        assertInvalid(p -> p.setGlobalBacklog(50_001));
        assertInvalid(p -> p.setSlots(0));
        assertInvalid(p -> p.setSlots(9));
        assertInvalid(p -> p.setConnectSeconds(0));
        assertInvalid(p -> p.setConnectSeconds(11));
        assertInvalid(p -> { p.setConnectSeconds(5); p.setReadSeconds(4); });
        assertInvalid(p -> p.setReadSeconds(31));
        assertInvalid(p -> { p.setReadSeconds(15); p.setBudgetSeconds(14); });
        assertInvalid(p -> p.setBudgetSeconds(61));
        assertInvalid(p -> { p.setBudgetSeconds(30); p.setLeaseSeconds(40); });
        assertInvalid(p -> p.setLeaseSeconds(301));
        assertInvalid(p -> p.setRetentionDays(0));
        assertInvalid(p -> p.setRetentionDays(31));
        assertInvalid(p -> p.setMaxArchiveBytes(1023));
        assertInvalid(p -> p.setMaxArchiveBytes(8 * 1024 * 1024 + 1));
        assertInvalid(p -> p.setMaxExpandedBytes(1023));
        assertInvalid(p -> p.setMaxExpandedBytes(32 * 1024 * 1024 + 1));
        assertInvalid(p -> p.setMaxEntryBytes(1023));
        assertInvalid(p -> p.setMaxEntryBytes(2 * 1024 * 1024 + 1));
        assertInvalid(p -> { p.setMaxExpandedBytes(4096); p.setMaxEntryBytes(4097); });
        assertInvalid(p -> p.setMaxEntries(0));
        assertInvalid(p -> p.setMaxEntries(201));
        assertInvalid(p -> p.setMaxJobPages(0));
        assertInvalid(p -> p.setMaxJobPages(6));
        assertInvalid(p -> p.setMaxArchiveHops(0));
        assertInvalid(p -> p.setMaxArchiveHops(4));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "*.actions.githubusercontent.com",
            "evilactions.githubusercontent.com",
            "evilblob.core.windows.net",
            "evil.com",
            "RESULTS-RECEIVER.actions.githubusercontent.com",
            "a..b.actions.githubusercontent.com",
            ".actions.githubusercontent.com"
    })
    void rejectsUnsafeArchiveHosts(String host) {
        assertInvalid(p -> p.setArchiveHosts(Set.of(host)));
    }

    @Test
    void rejectsEmptyArchiveHostsAndInvalidCredentials() {
        assertInvalid(p -> p.setArchiveHosts(Set.of()));

        assertInvalid(p -> p.setCredentials(Map.of(UUID.randomUUID(), credential("0", "github_pat_0123456789abcdefghij"))));
        assertInvalid(p -> p.setCredentials(Map.of(UUID.randomUUID(), credential("-5", "github_pat_0123456789abcdefghij"))));
        assertInvalid(p -> p.setCredentials(Map.of(UUID.randomUUID(), credential("abc", "github_pat_0123456789abcdefghij"))));
        assertInvalid(p -> p.setCredentials(Map.of(UUID.randomUUID(), credential("123", "short"))));
        assertInvalid(p -> p.setCredentials(Map.of(UUID.randomUUID(), credential("123", "bad token with spaces 0123456789"))));

        var withNull = new LinkedHashMap<UUID, GitHubActionsProperties.Credential>();
        withNull.put(UUID.randomUUID(), null);
        assertInvalid(p -> p.setCredentials(withNull));
    }

    private static void assertInvalid(java.util.function.Consumer<GitHubActionsProperties> mutator) {
        var properties = new GitHubActionsProperties();
        mutator.accept(properties);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    private static GitHubActionsProperties.Credential credential(String repositoryId, String token) {
        var value = new GitHubActionsProperties.Credential();
        value.setRepositoryId(repositoryId);
        value.setToken(token);
        return value;
    }
}
