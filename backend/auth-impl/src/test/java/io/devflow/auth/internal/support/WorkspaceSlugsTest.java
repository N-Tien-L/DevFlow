package io.devflow.auth.internal.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkspaceSlugs Unit Tests")
class WorkspaceSlugsTest {

    @Test
    @DisplayName("Strips Vietnamese diacritics and collapses separators")
    void stripsDiacritics() {
        assertThat(WorkspaceSlugs.base("Dự án Đồ án — DevFlow!")).isEqualTo("du-an-do-an-devflow");
    }

    @Test
    @DisplayName("Falls back to 'workspace' when nothing Latin remains")
    void fallsBackForNonLatinNames() {
        assertThat(WorkspaceSlugs.base("开发团队")).isEqualTo("workspace");
        assertThat(WorkspaceSlugs.base("---")).isEqualTo("workspace");
    }

    @Test
    @DisplayName("Caps the base length without leaving a trailing dash")
    void capsLength() {
        String base = WorkspaceSlugs.base("a".repeat(47) + " b" + "c".repeat(60));
        assertThat(base).hasSizeLessThanOrEqualTo(WorkspaceSlugs.MAX_BASE_LENGTH).doesNotEndWith("-");
    }

    @Test
    @DisplayName("Generated slugs carry a random suffix and fit the column")
    void generatesSuffixedSlug() {
        String slug = WorkspaceSlugs.generate("Team Alpha");
        assertThat(slug).matches("team-alpha-[a-z0-9]{" + WorkspaceSlugs.SUFFIX_LENGTH + "}");
        assertThat(WorkspaceSlugs.generate("x".repeat(100))).hasSizeLessThanOrEqualTo(100);
        assertThat(WorkspaceSlugs.generate("Team Alpha")).isNotEqualTo(slug);
    }
}
