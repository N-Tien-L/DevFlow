package io.devflow.gitci.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.devflow.common.event.TaskReferencesResolveRequestedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

class GitDomainEventSelectionTest {
    @Test void failedCommitInsertCannotWriteLinksAgainstAnUnpersistedUuid() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        GitDomainEventOutbox outbox=mock(GitDomainEventOutbox.class);
        ApplicationEventPublisher events=event -> {
            var request=(TaskReferencesResolveRequestedEvent)event;
            request.respond(java.util.Map.of("T-001",new TaskReferencesResolveRequestedEvent.Resolution(
                    request.workspaceId(),"T-001",UUID.randomUUID(),TaskReferencesResolveRequestedEvent.Outcome.ELIGIBLE)));
        };
        var ingestion=new GitWebhookIngestion(jdbc,events,new GitReferenceSourcePolicy(new TaskReferenceParser()),new SimpleMeterRegistry(),outbox);
        var root=new ObjectMapper().readTree("""
                {"ref":"refs/heads/main","deleted":false,"after":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "commits":[{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","message":"T-001","timestamp":"2026-11-01T12:00:00Z"}]}
                """);
        var plan=ingestion.prepare("push",root,"123");
        ingestion.persist(UUID.randomUUID(),UUID.randomUUID(),"https://github.com/test/repo",plan);
        verify(jdbc,never()).update(startsWith("INSERT INTO git_commit_task_links"),any(Object[].class));
        verify(outbox).enqueue(List.of());
    }

    @Test void retryDelayIsExponentialAndBounded() {
        assertThat(GitDomainEventOutbox.backoffSeconds(1)).isEqualTo(1);
        assertThat(GitDomainEventOutbox.backoffSeconds(2)).isEqualTo(2);
        assertThat(GitDomainEventOutbox.backoffSeconds(8)).isEqualTo(128);
        assertThat(GitDomainEventOutbox.backoffSeconds(Integer.MAX_VALUE)).isLessThanOrEqualTo(300);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={0,2})
    void missingOrAmbiguousResolverResponseCannotEnqueueEvents(int responses) throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        GitDomainEventOutbox outbox=mock(GitDomainEventOutbox.class);
        ApplicationEventPublisher events=event -> {
            var request=(TaskReferencesResolveRequestedEvent)event;
            var result=java.util.Map.of("T-001",new TaskReferencesResolveRequestedEvent.Resolution(
                    request.workspaceId(),"T-001",UUID.randomUUID(),TaskReferencesResolveRequestedEvent.Outcome.ELIGIBLE));
            for(int i=0;i<responses;i++) request.respond(result);
        };
        var ingestion=new GitWebhookIngestion(jdbc,events,new GitReferenceSourcePolicy(new TaskReferenceParser()),new SimpleMeterRegistry(),outbox);
        var root=new ObjectMapper().readTree("""
                {"ref":"refs/heads/main","deleted":false,"after":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "commits":[{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","message":"T-001","timestamp":"2026-11-01T12:00:00Z"}]}
                """);
        var plan=ingestion.prepare("push",root,"123");
        assertThatThrownBy(() -> ingestion.persist(UUID.randomUUID(),UUID.randomUUID(),"https://github.com/test/repo",plan))
                .isInstanceOfSatisfying(WebhookFailure.class,ex -> assertThat(ex.status).isEqualTo(503));
        verifyNoInteractions(outbox);
    }
}
