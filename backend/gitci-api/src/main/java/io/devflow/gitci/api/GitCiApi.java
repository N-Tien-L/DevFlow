package io.devflow.gitci.api;

import java.util.Optional;

/**
 * Public interface of the Git &amp; CI module (ARCHITECTURE.md Section 3: owns Git webhook
 * ingestion, commit/PR → task linking, CI log ingestion). This is the ONLY way other
 * modules may query Git/CI state synchronously — e.g. the AI service needs it for
 * in-editor CI failure lookup (PRODUCT_SPEC.md Section 5.2c).
 *
 * <p>Implementations live in {@code gitci-impl} and are never imported directly.
 */
public interface GitCiApi {

    /** Looks up a recorded CI failure by pipeline id. */
    Optional<CiFailureInfo> findFailure(String pipelineId);
}
