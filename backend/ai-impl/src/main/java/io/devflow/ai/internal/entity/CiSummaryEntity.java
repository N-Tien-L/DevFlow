package io.devflow.ai.internal.entity;

import io.devflow.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Persisted summary of a CI pipeline failure (T-031). Ensures idempotency so the same pipeline
 * run is never summarized more than once, and provides instant lookup for Board and MCP queries.
 */
@Entity
@Table(name = "ai_ci_summaries")
public class CiSummaryEntity extends BaseEntity {

    @Column(name = "pipeline_id", nullable = false, unique = true)
    private String pipelineId;

    @Column(name = "repo")
    private String repo;

    @Column(name = "commit_sha", length = 64)
    private String commitSha;

    @Column(name = "summary_markdown", nullable = false, columnDefinition = "TEXT")
    private String summaryMarkdown;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private CiSummarySource source;

    protected CiSummaryEntity() {
        // Required by JPA
    }

    public CiSummaryEntity(String pipelineId, String repo, String commitSha, String summaryMarkdown,
            CiSummarySource source) {
        this.pipelineId = Objects.requireNonNull(pipelineId, "pipelineId");
        this.repo = repo;
        this.commitSha = commitSha;
        this.summaryMarkdown = Objects.requireNonNull(summaryMarkdown, "summaryMarkdown");
        this.source = Objects.requireNonNull(source, "source");
    }

    public String getPipelineId() {
        return pipelineId;
    }

    public String getRepo() {
        return repo;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getSummaryMarkdown() {
        return summaryMarkdown;
    }

    public CiSummarySource getSource() {
        return source;
    }
}
