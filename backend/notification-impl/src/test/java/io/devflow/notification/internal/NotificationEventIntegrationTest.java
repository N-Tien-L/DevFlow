package io.devflow.notification.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.devflow.auth.api.AuthApi;
import io.devflow.board.api.BoardApi;
import io.devflow.board.api.TaskSummary;
import io.devflow.common.event.*;
import io.devflow.common.security.AuthenticatedActor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringJUnitConfig(NotificationEventIntegrationTest.Config.class)
class NotificationEventIntegrationTest {
    static final UUID TASK = UUID.randomUUID(), WORKSPACE = UUID.randomUUID(), RECIPIENT = UUID.randomUUID();
    static final UUID ACTOR = UUID.randomUUID();
    @Autowired ApplicationEventPublisher publisher;
    @Autowired BoardApi board;
    @Autowired AuthApi auth;
    @Autowired NotificationService service;
    @Autowired DataSourceTransactionManager manager;

    @BeforeEach void setup() {
        reset(board, auth, service);
        when(board.findTask(TASK)).thenReturn(Optional.of(new TaskSummary(TASK, WORKSPACE, "secret", "TODO", RECIPIENT, null)));
        when(auth.isWorkspaceMember(RECIPIENT, WORKSPACE)).thenReturn(true);
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    static Stream<DevFlowEvent> events() {
        return Stream.of(
            new TaskCreatedEvent(TASK, WORKSPACE, "secret", UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), ACTOR, Instant.now()),
            new TaskStatusChangedEvent(TASK, "Todo", "Done", TaskStatusChangedEvent.StatusChangeCause.MANUAL,
                    UUID.randomUUID(), WORKSPACE, UUID.randomUUID(), UUID.randomUUID(), "TODO", "DONE",
                    UUID.randomUUID(), UUID.randomUUID(), ACTOR, Instant.now()),
            new GitPrOpenedEvent(TASK, "https://example.test/pr/1", "private/repo"),
            new GitPrMergedEvent(TASK, "https://example.test/pr/1", "private/repo"),
            new CiFailureSummarizedEvent("pipeline", "secret", TASK),
            new RiskDeadlineFlaggedEvent(TASK, null, "secret", RiskDeadlineFlaggedEvent.Severity.HIGH));
    }

    @ParameterizedTest @MethodSource("events")
    void eachEventIsDeliveredAfterCommitToAssignee(DevFlowEvent event) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            verifyNoInteractions(service);
        });
        verify(service).deliver(eq(RECIPIENT), any(UUID.class), eq(WORKSPACE), eq(TASK),
                eq(event.eventType()), anyString(), anyString());
    }

    @ParameterizedTest @MethodSource("events")
    void noneOfTheSixEventsNotifyTheActor(DevFlowEvent event) {
        UUID actor = event instanceof TaskCreatedEvent e ? e.actorId()
                : event instanceof TaskStatusChangedEvent e ? e.actorId() : RECIPIENT;
        when(board.findTask(TASK)).thenReturn(Optional.of(new TaskSummary(TASK, WORKSPACE, "task", "TODO", actor, null)));
        if (actor.equals(RECIPIENT)) {
            AuthenticatedActor principal = () -> RECIPIENT;
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        }
        publisher.publishEvent(event);
        verifyNoInteractions(service);
    }

    @ParameterizedTest @MethodSource("events")
    void rolledBackEventsAreNotDelivered(DevFlowEvent event) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            publisher.publishEvent(event);
            status.setRollbackOnly();
        });
        verifyNoInteractions(service);
    }

    @ParameterizedTest @MethodSource("events")
    void legacyReplayUsesTheSameIdAndExplicitIdsArePreserved(DevFlowEvent event) {
        publisher.publishEvent(event);
        publisher.publishEvent(event);
        ArgumentCaptor<UUID> ids = ArgumentCaptor.forClass(UUID.class);
        verify(service, times(2)).deliver(eq(RECIPIENT), ids.capture(), eq(WORKSPACE), eq(TASK),
                eq(event.eventType()), anyString(), anyString());
        assertThat(ids.getAllValues().get(0)).isEqualTo(ids.getAllValues().get(1));
        if (event instanceof TaskCreatedEvent e) { assertThat(ids.getValue()).isEqualTo(e.eventId()); }
        if (event instanceof TaskStatusChangedEvent e) { assertThat(ids.getValue()).isEqualTo(e.eventId()); }
    }

    @Test void supportsBoardPublisherThatEmitsInsideAfterCommit() {
        new TransactionTemplate(manager).executeWithoutResult(status ->
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publisher.publishEvent(events().findFirst().orElseThrow()); }
            }));
        verify(service).deliver(eq(RECIPIENT), any(), eq(WORKSPACE), eq(TASK), anyString(), anyString(), anyString());
    }

    @Test void failureIsContainedAndNextEventStillRuns() {
        doThrow(new IllegalStateException("sensitive content")).doNothing().when(service)
                .deliver(any(), any(), any(), any(), anyString(), anyString(), anyString());
        assertThatCode(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                publisher.publishEvent(events().findFirst().orElseThrow()))).doesNotThrowAnyException();
        publisher.publishEvent(events().findFirst().orElseThrow());
        verify(service, times(2)).deliver(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test void missingTaskAssigneeMembershipAndSprintOnlyEventsAreSkipped() {
        when(auth.isWorkspaceMember(RECIPIENT, WORKSPACE)).thenReturn(false);
        publisher.publishEvent(events().findFirst().orElseThrow());
        when(board.findTask(TASK)).thenReturn(Optional.empty());
        publisher.publishEvent(events().findFirst().orElseThrow());
        when(board.findTask(TASK)).thenReturn(Optional.of(new TaskSummary(TASK, WORKSPACE, "task", "TODO", null, null)));
        publisher.publishEvent(events().findFirst().orElseThrow());
        publisher.publishEvent(new RiskDeadlineFlaggedEvent(null, UUID.randomUUID(), "risk", RiskDeadlineFlaggedEvent.Severity.HIGH));
        publisher.publishEvent(new CiFailureSummarizedEvent("run", "summary", null));
        verifyNoInteractions(service);
    }

    @Configuration
    static class Config {
        @Bean static org.springframework.transaction.event.TransactionalEventListenerFactory eventListenerFactory() {
            return new org.springframework.transaction.event.TransactionalEventListenerFactory();
        }
        @Bean BoardApi board() { return mock(BoardApi.class); }
        @Bean AuthApi auth() { return mock(AuthApi.class); }
        @Bean NotificationService service() { return mock(NotificationService.class); }
        @Bean DataSource dataSource() { return new DriverManagerDataSource("jdbc:h2:mem:notificationEvents;DB_CLOSE_DELAY=-1", "sa", ""); }
        @Bean DataSourceTransactionManager manager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean NotificationEventListeners listener(BoardApi board, AuthApi auth, NotificationService service) {
            return new NotificationEventListeners(board, auth, service, new ObjectMapper().findAndRegisterModules(), new SimpleMeterRegistry());
        }
    }
}
