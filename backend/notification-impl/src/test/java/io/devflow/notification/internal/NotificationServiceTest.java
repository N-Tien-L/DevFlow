package io.devflow.notification.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.devflow.notification.api.NotificationResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class NotificationServiceTest {
    final NotificationRepository repository = mock(NotificationRepository.class);
    final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    final UUID user = UUID.randomUUID(), event = UUID.randomUUID();
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    NotificationService service(MeterRegistry registry) {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
        return new NotificationService(repository, messaging, registry, new DataSourceTransactionManager(source));
    }
    void deliver(NotificationService service) {
        service.deliver(user, event, null, null, "task.created", "<b>Assigned</b>", "<p>A task was assigned.</p>");
    }

    @Test void onlyNewRowsArePushedAndCountedAndContentIsPlainText() {
        when(repository.insert(eq(user), any())).thenReturn(true, false);
        var service = service(metrics);
        deliver(service); deliver(service);
        var payload = ArgumentCaptor.forClass(NotificationResponse.class);
        verify(messaging).convertAndSendToUser(eq(user.toString()), eq("/queue/notifications"), payload.capture());
        assertThat(payload.getValue().title()).isEqualTo("Assigned");
        assertThat(payload.getValue().content()).isEqualTo("A task was assigned.");
        assertThat(metrics.get("devflow.notification.delivered").counter().count()).isEqualTo(1);
    }

    @Test void aPersistenceFailureNeverPushesUncommittedData() {
        when(repository.insert(any(), any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> deliver(service(metrics))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(messaging);
        assertThat(metrics.find("devflow.notification.delivered").counter()).isNull();
    }

    @Test void metricsFailureDoesNotPreventRealtimeDelivery() {
        MeterRegistry brokenMetrics = mock(MeterRegistry.class);
        when(brokenMetrics.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("metrics unavailable"));
        when(repository.insert(any(), any())).thenReturn(true);
        assertThatCode(() -> deliver(service(brokenMetrics))).doesNotThrowAnyException();
        verify(messaging).convertAndSendToUser(eq(user.toString()), eq("/queue/notifications"), any(Object.class));
    }

    @Test void invalidSystemTextIsRejectedBeforePersistence() {
        var service = service(metrics);
        assertThatThrownBy(() -> service.sendToUser(user, "<b> </b>", "body")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.sendToUser(user, "a".repeat(256), "body")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.sendToUser(user, "title", "a".repeat(4001))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, messaging);
    }
}
