package io.devflow.gitci.internal;

import io.devflow.gitci.api.CiFailureInfo;
import io.devflow.gitci.api.GitCiApi;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Retains the legacy interface while CI excerpts are served by CiFailureLogReader. */
@Service
public class GitCiService implements GitCiApi {

    @Override
    @Deprecated
    public Optional<CiFailureInfo> findFailure(String pipelineId) {
        throw new UnsupportedOperationException("CI log reads require the typed, workspace-scoped event");
    }
}
