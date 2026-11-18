package io.devflow.gitci.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GitHubWebhookPropertiesTest {
    @Test
    void rejectsDuplicateKeyMaterialAcrossBindings() {
        var properties = new GitHubWebhookProperties();
        properties.setEnabled(true);
        var first = binding("first", "01234567890123456789012345678901");
        var second = binding("second", "01234567890123456789012345678901");
        properties.setBindings(Map.of(UUID.randomUUID(), first, UUID.randomUUID(), second));
        assertThrows(IllegalStateException.class, properties::validate);
    }

    @Test
    void validatesRotationAndDisabledMode() {
        var properties = new GitHubWebhookProperties();
        assertDoesNotThrow(properties::validate);
        properties.setEnabled(true);
        var binding = binding("reference", "01234567890123456789012345678901");
        binding.setPrevious("another-test-key-0123456789012345");
        properties.setBindings(Map.of(UUID.randomUUID(), binding));
        assertThrows(IllegalStateException.class, properties::validate);
        binding.setPreviousValidUntil(Instant.parse("2030-01-01T00:00:00Z"));
        assertDoesNotThrow(properties::validate);
    }

    private static GitHubWebhookProperties.Binding binding(String reference, String key) {
        var result = new GitHubWebhookProperties.Binding();
        result.setReference(reference);
        result.setCurrent(key);
        return result;
    }
}
