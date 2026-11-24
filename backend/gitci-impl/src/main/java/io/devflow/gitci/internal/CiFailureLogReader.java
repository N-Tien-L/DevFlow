package io.devflow.gitci.internal;

import io.devflow.common.event.CiFailureDetectedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent;
import io.devflow.common.event.CiFailureLogReadRequestedEvent.Response;
import io.devflow.common.event.CiFailureLogReadRequestedEvent.Status;
import io.devflow.common.event.WorkspaceMembershipCheckRequestedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class CiFailureLogReader {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final MeterRegistry metrics;

    CiFailureLogReader(JdbcTemplate jdbc, ApplicationEventPublisher events, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.events = events;
        this.metrics = metrics;
    }

    @EventListener
    void read(CiFailureLogReadRequestedEvent request) {
        Response response = lookup(request);
        request.respond(response);
        metrics.counter("devflow.gitci.ci.log.reads", "outcome", response.status().name()).increment();
    }

    private record Stored(UUID workspace, UUID repository, UUID event, UUID snapshotEvent, UUID snapshotRepository,
                          UUID snapshotWorkspace, String status, boolean expired, String excerpt, boolean truncated,
                          String digest, String run, int number, String sha, String repo, Instant occurredAt) { }

    private Response lookup(CiFailureLogReadRequestedEvent request) {
        UUID failure = failureId(request.reference());
        if (request.pipelineId() == null || request.workspaceId() == null || failure == null)
            return Response.status(Status.DENIED);
        CiFailureDetectedEvent supplied = request.detection();
        if (supplied == null && !isMember(request)) return Response.status(Status.DENIED);
        if (supplied != null && (supplied.eventId() == null || supplied.repositoryId() == null
                || !request.pipelineId().toString().equals(supplied.pipelineId())
                || supplied.providerRunId() == null || supplied.runAttempt() <= 0))
            return Response.status(Status.DENIED);
        List<Stored> rows = jdbc.query("""
                SELECT r.workspace_id,r.id AS repository_id,f.log_event_id,o.id AS snapshot_event,
                    o.repository_id AS snapshot_repository,o.workspace_id AS snapshot_workspace,
                    f.log_status,COALESCE(f.log_expires_at<=clock_timestamp(),false) AS expired,
                    CASE WHEN r.workspace_id=? AND f.log_status='READY' AND f.log_expires_at>clock_timestamp()
                        THEN f.raw_log_snippet END AS excerpt,f.log_truncated,f.log_digest,
                    p.pipeline_id,p.run_attempt,p.commit_sha,o.repo,o.occurred_at
                FROM ci_failures f JOIN ci_pipelines p ON p.id=f.pipeline_id JOIN repositories r ON r.id=p.repository_id
                LEFT JOIN ci_failure_event_outbox o ON o.id=f.log_event_id
                WHERE f.id=? AND p.id=? AND f.failure_key='workflow-run'
                """, (rs, n) -> {
                    var occurred = rs.getTimestamp("occurred_at");
                    return new Stored(rs.getObject("workspace_id", UUID.class), rs.getObject("repository_id", UUID.class),
                        rs.getObject("log_event_id", UUID.class), rs.getObject("snapshot_event", UUID.class),
                        rs.getObject("snapshot_repository", UUID.class), rs.getObject("snapshot_workspace", UUID.class),
                        rs.getString("log_status"), rs.getBoolean("expired"), rs.getString("excerpt"),
                        rs.getBoolean("log_truncated"), rs.getString("log_digest"), rs.getString("pipeline_id"),
                        rs.getInt("run_attempt"), rs.getString("commit_sha"), rs.getString("repo"),
                        occurred == null ? null : occurred.toInstant());
                }, request.workspaceId(), failure, request.pipelineId());
        if (rows.isEmpty()) return Response.status(Status.NOT_FOUND);
        Stored stored = rows.get(0);
        if (!request.workspaceId().equals(stored.workspace()) || (supplied != null && !matches(supplied, stored)))
            return Response.status(Status.DENIED);
        if (stored.expired() || stored.status().equals("EXPIRED")) return Response.status(Status.EXPIRED);
        if (!stored.status().equals("READY") || stored.excerpt() == null) return Response.status(Status.NOT_READY);
        String excerpt = CiLogSanitizer.limitUtf8(stored.excerpt(), request.maxBytes());
        if (excerpt.isBlank()) return Response.status(Status.NOT_READY);
        return new Response(Status.READY, excerpt, stored.truncated() || !excerpt.equals(stored.excerpt()), stored.digest());
    }

    private static boolean matches(CiFailureDetectedEvent event, Stored stored) {
        if (!event.eventId().equals(stored.event()) || !event.repositoryId().equals(stored.repository())
                || !event.providerRunId().equals(stored.run()) || event.runAttempt() != stored.number()
                || !Objects.equals(event.commitSha(), stored.sha())) return false;
        if (stored.snapshotEvent() == null) return stored.expired() || stored.status().equals("EXPIRED");
        return event.repositoryId().equals(stored.snapshotRepository()) && event.workspaceId().equals(stored.snapshotWorkspace())
                && Objects.equals(event.repo(), stored.repo()) && Objects.equals(event.occurredAt(), stored.occurredAt());
    }

    private boolean isMember(CiFailureLogReadRequestedEvent request) {
        if (request.actorId() == null) return false;
        var membership = new WorkspaceMembershipCheckRequestedEvent(request.correlationId(), request.actorId(), request.workspaceId());
        events.publishEvent(membership);
        return membership.responseCount() == 1 && membership.decision() == WorkspaceMembershipCheckRequestedEvent.Decision.MEMBER;
    }

    private static UUID failureId(String reference) {
        if (reference == null || !reference.matches("ci-log:[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return null;
        return UUID.fromString(reference.substring("ci-log:".length()));
    }
}
