package io.devflow.gitci.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.devflow.integration.MultiModulePostgresSupport;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Thin test-only bridge inside {@code :app} exposing package-private CI worker entrypoints
 * and stubbing only the external GitHub Actions HTTP boundary so {@code io.devflow.integration}
 * tests can drive the real CI -> AI -> Notification pipeline deterministically without modifying
 * {@code gitci-impl} production visibility.
 */
public abstract class CiMultiModuleTestBase extends MultiModulePostgresSupport {

    @Autowired
    private CiLogFetchWorker ciFetchWorker;

    @Autowired
    private CiFailureEventOutbox ciFailureEventOutbox;

    @MockitoBean
    private GitHubActionsReadClient githubActionsReadClient;

    protected void runCiFetchOne() {
        ciFetchWorker.runOne();
    }

    protected void dispatchCiOutbox() {
        ciFailureEventOutbox.dispatch();
    }

    protected void resetGithubActionsStub() {
        reset(githubActionsReadClient);
    }

    protected void stubGithubActionsLog(String logContent) throws Exception {
        when(githubActionsReadClient.fetch(any(), anyLong())).thenReturn(zipArchive(logContent));
    }

    protected void stubGithubActionsLogs(String firstLog, String secondLog) throws Exception {
        when(githubActionsReadClient.fetch(any(), anyLong()))
                .thenReturn(zipArchive(firstLog))
                .thenReturn(zipArchive(secondLog));
    }

    private static GitHubActionsReadClient.Archive zipArchive(String content) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("0_test_job.txt"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new GitHubActionsReadClient.Archive(
                bytes.toByteArray(),
                List.of(new GitHubActionsReadClient.Job(1L, "test_job", true, List.of())),
                false);
    }
}
