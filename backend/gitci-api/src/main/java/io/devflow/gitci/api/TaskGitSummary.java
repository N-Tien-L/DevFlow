package io.devflow.gitci.api;

import java.util.UUID;

/** Compact Git &amp; CI badge summary rendered on a Kanban task card. */
public record TaskGitSummary(
        UUID taskId,
        int commitCount,
        TaskGitCommitSummary latestCommit,
        int prCount,
        int openPrCount,
        TaskGitPrSummary primaryPr,
        TaskGitCiSummary ci) {
}
