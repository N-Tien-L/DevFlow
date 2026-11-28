package io.devflow.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T-041 Suite 6 ({@code ARCH-02, ARCH-03}): static source-level architecture verification
 * complementing Gradle's {@code :verifyModuleBoundaries} task (ARCHITECTURE.md Section 10).
 *
 * <p>Runs without Spring or Docker so violations fail fast in milliseconds.
 */
class ModuleBoundaryArchitectureTest {

    private static final Set<String> MODULES = Set.of("auth", "board", "gitci", "ai", "notification");

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?(io\\.devflow\\.([a-z]+)\\.(internal|api)\\.[^;\\s]+)\\s*;",
            Pattern.MULTILINE);

    private static final Pattern FORBIDDEN_API_ANNOTATION = Pattern.compile(
            "(?m)^\\s*@(Entity|Repository|Service|RestController|Controller|Component)\\b");

    @Test
    void arch02_productionSourcesObeyStrictModuleImportAndApiPurityRules() throws IOException {
        Path backendRoot = resolveBackendRoot();
        List<String> violations = new ArrayList<>();

        // 1. :common must not import any business module package
        violations.addAll(scanModuleSources(backendRoot.resolve("common/src/main/java"), "common", ModuleKind.COMMON));

        // 2. Each -api module must stay pure (no internal imports, no foreign module imports, no impl annotations)
        // 3. Each -impl module must never import another module's .internal.* package
        for (String module : MODULES) {
            violations.addAll(scanModuleSources(
                    backendRoot.resolve(module + "-api/src/main/java"), module, ModuleKind.API));
            violations.addAll(scanModuleSources(
                    backendRoot.resolve(module + "-impl/src/main/java"), module, ModuleKind.IMPL));
        }

        assertThat(violations)
                .as("Detected module boundary violations across backend production sources")
                .isEmpty();
    }

    @Test
    void arch03_validatorReliablyDetectsSyntheticBoundaryViolations() {
        String crossInternalImport = """
                package io.devflow.board.internal;
                import io.devflow.gitci.internal.GitDomainEventOutbox;
                class BadBoardConsumer {}
                """;
        assertThat(validateSource("board-impl/BadBoardConsumer.java", "board", ModuleKind.IMPL, crossInternalImport))
                .singleElement()
                .asString()
                .contains("forbidden cross-module internal import")
                .contains("io.devflow.gitci.internal.GitDomainEventOutbox");

        String entityInApi = """
                package io.devflow.board.api;
                import jakarta.persistence.Entity;
                @Entity
                public class BadApiEntity {}
                """;
        assertThat(validateSource("board-api/BadApiEntity.java", "board", ModuleKind.API, entityInApi))
                .singleElement()
                .asString()
                .contains("forbidden implementation annotation")
                .contains("@Entity");

        String commonImportingModule = """
                package io.devflow.common.event;
                import io.devflow.auth.api.AuthApi;
                public class BadCommonEvent {}
                """;
        assertThat(validateSource("common/BadCommonEvent.java", "common", ModuleKind.COMMON, commonImportingModule))
                .singleElement()
                .asString()
                .contains(":common must not import business module")
                .contains("io.devflow.auth.api.AuthApi");

        String apiImportingForeignApi = """
                package io.devflow.board.api;
                import io.devflow.auth.api.AuthApi;
                public interface BadCrossApi {}
                """;
        assertThat(validateSource("board-api/BadCrossApi.java", "board", ModuleKind.API, apiImportingForeignApi))
                .singleElement()
                .asString()
                .contains("-api must not import foreign module")
                .contains("io.devflow.auth.api.AuthApi");
    }

    private static List<String> scanModuleSources(Path sourceRoot, String ownerModule, ModuleKind kind)
            throws IOException {
        assertThat(sourceRoot).as("Expected source directory %s", sourceRoot).isDirectory();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            List<Path> javaFiles = paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            assertThat(javaFiles).as("Expected at least one Java file in %s", sourceRoot).isNotEmpty();
            for (Path file : javaFiles) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                violations.addAll(validateSource(sourceRoot.relativize(file).toString(), ownerModule, kind, content));
            }
        }
        return violations;
    }

    static List<String> validateSource(String label, String ownerModule, ModuleKind kind, String content) {
        List<String> violations = new ArrayList<>();
        Matcher importMatcher = IMPORT_PATTERN.matcher(content);
        while (importMatcher.find()) {
            String fullImport = importMatcher.group(1);
            String targetModule = importMatcher.group(2);
            String targetLayer = importMatcher.group(3);
            if (!MODULES.contains(targetModule)) {
                continue;
            }
            switch (kind) {
                case COMMON -> violations.add(label + ": :common must not import business module '" + fullImport + "'");
                case API -> {
                    if ("internal".equals(targetLayer)) {
                        violations.add(label + ": -api must not import internal class '" + fullImport + "'");
                    } else if (!targetModule.equals(ownerModule)) {
                        violations.add(label + ": -api must not import foreign module '" + fullImport + "'");
                    }
                }
                case IMPL -> {
                    if ("internal".equals(targetLayer) && !targetModule.equals(ownerModule)) {
                        violations.add(label + ": forbidden cross-module internal import '" + fullImport + "'");
                    }
                }
            }
        }
        if (kind == ModuleKind.API) {
            Matcher annotationMatcher = FORBIDDEN_API_ANNOTATION.matcher(content);
            while (annotationMatcher.find()) {
                violations.add(label + ": forbidden implementation annotation '@" + annotationMatcher.group(1) + "' in -api");
            }
        }
        return violations;
    }

    private static Path resolveBackendRoot() {
        for (Path candidate : List.of(Path.of(".."), Path.of("."), Path.of("backend"))) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized.resolve("settings.gradle.kts"))
                    && Files.isDirectory(normalized.resolve("common"))) {
                return normalized;
            }
        }
        throw new IllegalStateException("Could not locate DevFlow backend root directory from " + Path.of("").toAbsolutePath());
    }

    enum ModuleKind {
        COMMON,
        API,
        IMPL
    }
}
