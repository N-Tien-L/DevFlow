package io.devflow.gitci.internal;

import io.devflow.gitci.api.CiFailureInfo;
import io.devflow.gitci.api.GitCiApi;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Git &amp; CI module core service. Owns: Git webhook ingestion, commit/PR → task linking,
 * CI log ingestion (ARCHITECTURE.md Section 3). Does NOT own task display logic or AI
 * summarization — it publishes events and lets Board / AI service react (Section 4).
 *
 * <p>TODO(PRODUCT_SPEC Section 5.2a — Git &amp; CI/CD Integration):
 * <ul>
 *   <li>Task ↔ commit/PR linking via the task-ID naming convention in branch/commit names
 *       → publish {@code GitCommitLinkedEvent}</li>
 *   <li>PR opened/merged webhooks → publish {@code GitPrOpenedEvent} /
 *       {@code GitPrMergedEvent}</li>
 *   <li>CI failure ingestion (store raw log, keep a reference) → publish
 *       {@code CiFailureDetectedEvent}</li>
 *   <li>Failure clustering and failure-to-task/PR linking build on top of these.</li>
 * </ul>
 */
@Service
public class GitCiService implements GitCiApi {

    private final ApplicationEventPublisher eventPublisher;

    public GitCiService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Optional<CiFailureInfo> findFailure(String pipelineId) {
        // TODO(PRODUCT_SPEC 5.2a): look up the recorded failure and map it to CiFailureInfo.
        throw new UnsupportedOperationException("Not implemented yet — scaffold stub");
    }
}
