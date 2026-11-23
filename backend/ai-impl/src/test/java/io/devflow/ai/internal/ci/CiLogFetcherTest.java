package io.devflow.ai.internal.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

@DisplayName("CiLogFetcher Security & Resolution Unit Tests")
class CiLogFetcherTest {

    @Test
    void returnsInlineLogTextAndCapsAtMaxFetchBytes() {
        RestClient restClient = mock(RestClient.class);
        CiLogFetcher fetcher = new CiLogFetcher(restClient, List.of("api.github.com"));

        String inline = "[ERROR] Compilation failed in App.java:42";
        assertThat(fetcher.fetch(inline)).isEqualTo(inline);

        String oversized = "x".repeat(CiLogFetcher.MAX_FETCH_BYTES + 1024);
        assertThat(fetcher.fetch(oversized)).hasSize(CiLogFetcher.MAX_FETCH_BYTES);
        verifyNoInteractions(restClient);
    }

    @Test
    void rejectsFileUriAndLocalFilePaths(@TempDir Path tempDir) throws Exception {
        Path secretFile = tempDir.resolve("secret.txt");
        Files.writeString(secretFile, "TOP_SECRET_LOCAL_FILE_CONTENT");

        RestClient restClient = mock(RestClient.class);
        CiLogFetcher fetcher = new CiLogFetcher(restClient, List.of("api.github.com"));

        // file: URI must be rejected outright
        assertThat(fetcher.fetch(secretFile.toUri().toString())).isEmpty();
        // Raw filesystem path must NOT be read from disk
        assertThat(fetcher.fetch(secretFile.toString())).doesNotContain("TOP_SECRET_LOCAL_FILE_CONTENT");
        // classpath: must be rejected
        assertThat(fetcher.fetch("classpath:application.yml")).isEmpty();
        verifyNoInteractions(restClient);
    }

    @Test
    void rejectsHttpAndNonAllowlistedOrInternalHosts() {
        RestClient restClient = mock(RestClient.class);
        CiLogFetcher fetcher = new CiLogFetcher(restClient, List.of("api.github.com"));

        // Plain HTTP rejected even for allowlisted host
        assertThat(fetcher.fetch("http://api.github.com/repos/org/repo/actions/runs/1/logs")).isEmpty();
        // Non-allowlisted external host rejected
        assertThat(fetcher.fetch("https://evil.example.com/log.txt")).isEmpty();
        // Cloud metadata & loopback rejected
        assertThat(fetcher.fetch("https://169.254.169.254/latest/meta-data")).isEmpty();
        assertThat(fetcher.fetch("https://localhost:8080/actuator/env")).isEmpty();
        assertThat(fetcher.fetch("https://127.0.0.1/secret")).isEmpty();
        // Userinfo smuggling rejected
        assertThat(fetcher.fetch("https://user:pass@api.github.com/logs")).isEmpty();
        verifyNoInteractions(restClient);
    }

    @Test
    void rejectsAllowlistedHostIfItResolvesToLoopback() {
        RestClient restClient = mock(RestClient.class);
        // Even if localhost were misconfigured into the allowlist, private IP check must block it
        CiLogFetcher fetcher = new CiLogFetcher(restClient, List.of("localhost", "127.0.0.1"));

        assertThat(fetcher.fetch("https://localhost/actuator")).isEmpty();
        assertThat(fetcher.fetch("https://127.0.0.1/actuator")).isEmpty();
        verifyNoInteractions(restClient);
    }
}
