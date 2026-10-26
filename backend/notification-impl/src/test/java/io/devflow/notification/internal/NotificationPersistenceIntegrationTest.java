package io.devflow.notification.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.devflow.notification.api.NotificationResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL checks for ON CONFLICT, concurrency, migration constraints, and commit visibility. */
@Testcontainers(disabledWithoutDocker = true)
class NotificationPersistenceIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    UUID user;
    JdbcTemplate jdbc;
    NotificationRepository repository;
    NotificationService service;
    SimpMessagingTemplate messaging;
    SimpleMeterRegistry metrics;
    DataSourceTransactionManager manager;
    String schema;

    @BeforeEach void setup() {
        schema = "notification_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users (id UUID PRIMARY KEY)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V11__notification_schema.sql")).execute(source);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?)", user);
        manager = new DataSourceTransactionManager(source);
        repository = new NotificationRepository(jdbc);
        messaging = mock(SimpMessagingTemplate.class);
        metrics = new SimpleMeterRegistry();
        service = new NotificationService(repository, messaging, metrics, manager);
    }

    @AfterEach void cleanup() { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    void deliver(UUID eventId) {
        service.deliver(user, eventId, UUID.randomUUID(), UUID.randomUUID(), "task.created", "Task assigned", "A task was assigned.");
    }

    @Test void duplicatesPersistAndPushOnlyOnceAndAreVisibleBeforePush() {
        UUID eventId = UUID.randomUUID();
        doAnswer(call -> {
            // JdbcTemplate obtains another connection outside the delivery transaction.
            assertThat(repository.unreadCount(user)).isEqualTo(1);
            return null;
        }).when(messaging).convertAndSendToUser(eq(user.toString()), eq("/queue/notifications"), any(Object.class));
        deliver(eventId); deliver(eventId);
        assertThat(repository.unreadCount(user)).isEqualTo(1);
        verify(messaging, times(1)).convertAndSendToUser(eq(user.toString()), eq("/queue/notifications"), any(Object.class));
        assertThat(metrics.get("devflow.notification.delivered").tag("type", "task.created").counter().count()).isEqualTo(1);
    }

    @Test void concurrentReplaysAreAtomicAndTheSameEventMayHaveDifferentRecipients() throws Exception {
        UUID eventId = UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) { futures.add(executor.submit(() -> deliver(eventId))); }
            for (var future : futures) { future.get(20, java.util.concurrent.TimeUnit.SECONDS); }
        } finally { executor.shutdownNow(); }
        assertThat(repository.unreadCount(user)).isEqualTo(1);
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO users VALUES (?)", other);
        service.deliver(other, eventId, null, null, "task.created", "Task assigned", "A task was assigned.");
        assertThat(repository.unreadCount(other)).isEqualTo(1);
    }

    @Test void persistenceSurvivesRealtimeFailureAndOuterTransactionRollback() {
        doThrow(new IllegalStateException("sensitive details")).when(messaging)
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));
        new TransactionTemplate(manager).executeWithoutResult(status -> { deliver(UUID.randomUUID()); status.setRollbackOnly(); });
        assertThat(repository.unreadCount(user)).isEqualTo(1);
        assertThat(metrics.get("devflow.notification.realtime.failed").counter().count()).isEqualTo(1);
    }

    @Test void migrationRejectsInvalidRecipientsAndNullRequiredFieldsAndCascadesUserDeletion() {
        var notification = new NotificationResponse(UUID.randomUUID(), UUID.randomUUID(), null,
                "system", "Title", "Content", null, false, Instant.now(), null);
        assertThatThrownBy(() -> repository.insert(UUID.randomUUID(), notification)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notifications(event_id,recipient_id,type,title,content) VALUES (NULL,?,'system','Title','Content')", user))
                .isInstanceOf(DataIntegrityViolationException.class);
        deliver(UUID.randomUUID());
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        assertThat(repository.unreadCount(user)).isZero();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = 'notifications'", schema)).hasSize(5);
    }
}
