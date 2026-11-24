package io.devflow.gitci.api;

import java.util.Optional;

/**
 * Legacy Git/CI scaffold. CI excerpts are served through the authorized
 * {@code CiFailureLogReadRequestedEvent} contract in common.
 */
public interface GitCiApi {

    /** Unsupported legacy lookup. Use the typed event with workspace context. */
    @Deprecated
    Optional<CiFailureInfo> findFailure(String pipelineId);
}
