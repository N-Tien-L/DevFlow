package io.devflow.notification.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.devflow.common.security.AuthenticatedActor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationApiTest {
    final UUID user = UUID.randomUUID(), other = UUID.randomUUID();
    JdbcTemplate jdbc;
    NotificationService service;
    NotificationRepository repository;
    NotificationProperties properties;
    MockMvc mvc;
    UsernamePasswordAuthenticationToken principal;

    @BeforeEach void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE users (id UUID PRIMARY KEY)");
        try (var stream = getClass().getResourceAsStream("/db/migration/V11__notification_schema.sql")) {
            String schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "")
                    .replace("gen_random_uuid()", "random_uuid()").replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE");
            for (String statement : schema.split(";")) { if (!statement.isBlank()) { jdbc.execute(statement); } }
        }
        jdbc.update("INSERT INTO users VALUES (?), (?)", user, other);
        repository = new NotificationRepository(jdbc);
        service = new NotificationService(repository, mock(SimpMessagingTemplate.class), new SimpleMeterRegistry(),
                new DataSourceTransactionManager(source));
        properties = new NotificationProperties();
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(service, new NotificationRateLimiter(properties)))
                .setControllerAdvice(new NotificationExceptionHandler()).build();
        AuthenticatedActor actor = () -> user;
        principal = UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of());
    }

    UUID insert(UUID recipient, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO notifications (id,event_id,recipient_id,type,title,content,created_at) VALUES (?,?,?,'task.created','Title','Content',?)",
                id, UUID.randomUUID(), recipient, java.sql.Timestamp.from(createdAt));
        return id;
    }

    @Test void paginationCountsAndReadOperationsAreScopedToPrincipal() throws Exception {
        UUID first = insert(user, Instant.now().minusSeconds(10));
        UUID latest = insert(user, Instant.now());
        insert(other, Instant.now());
        mvc.perform(get("/api/v1/notifications").principal(principal).param("size", "1").param("userId", other.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(latest.toString())).andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/v1/notifications").principal(principal).param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.content[0].id").value(first.toString())).andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/notifications/unread-count").principal(principal))
                .andExpect(jsonPath("$.count").value(2));
        mvc.perform(patch("/api/v1/notifications/{id}/read", first).principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isRead").value(true));
        Instant readAt = repository.find(user, first).orElseThrow().readAt();
        mvc.perform(patch("/api/v1/notifications/{id}/read", first).principal(principal)).andExpect(status().isOk());
        assertThat(repository.find(user, first).orElseThrow().readAt()).isEqualTo(readAt);
        mvc.perform(post("/api/v1/notifications/read-all").principal(principal)).andExpect(jsonPath("$.updated").value(1));
        mvc.perform(post("/api/v1/notifications/read-all").principal(principal)).andExpect(jsonPath("$.updated").value(0));
        mvc.perform(get("/api/v1/notifications/unread-count").principal(principal)).andExpect(jsonPath("$.count").value(0));
        assertThat(repository.unreadCount(other)).isEqualTo(1);
    }

    @Test void foreignAndMissingNotificationsHaveIdentical404Responses() throws Exception {
        UUID foreign = insert(other, Instant.now());
        for (UUID id : List.of(foreign, UUID.randomUUID())) {
            mvc.perform(patch("/api/v1/notifications/{id}/read", id).principal(principal))
                    .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.detail").value("Notification not found."));
        }
        assertThat(repository.find(user, foreign)).isEmpty();
        assertThat(repository.find(other, foreign).orElseThrow().isRead()).isFalse();
    }

    @Test void rejectsAnonymousOversizedInvalidAndMalformedRequests() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        for (String size : List.of("0", "101", "bad")) {
            mvc.perform(get("/api/v1/notifications").principal(principal).param("size", size))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(get("/api/v1/notifications").principal(principal).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/notifications/not-a-uuid/read").principal(principal)).andExpect(status().isBadRequest());
    }

    @Test void rateLimitReturnsProblemDetailAndRetryAfter() throws Exception {
        properties.setRateLimitPerMinute(1);
        mvc.perform(get("/api/v1/notifications").principal(principal)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/notifications/read-all").principal(principal))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test void retentionPurgesOldRowsInBoundedBatches() {
        Instant old = Instant.now().minusSeconds(91 * 86400L);
        insert(user, old); insert(user, old); insert(user, old);
        UUID recent = insert(user, Instant.now());
        properties.setCleanupBatchSize(1); properties.setCleanupMaxBatches(2);
        var cleanup = new NotificationCleanup(repository, properties,
                new DataSourceTransactionManager(jdbc.getDataSource()));
        cleanup.purge();
        assertThat(repository.unreadCount(user)).isEqualTo(2);
        cleanup.purge();
        assertThat(repository.unreadCount(user)).isEqualTo(1);
        assertThat(repository.find(user, recent)).isPresent();
    }
}
