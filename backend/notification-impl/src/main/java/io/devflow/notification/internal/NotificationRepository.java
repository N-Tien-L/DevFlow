package io.devflow.notification.internal;

import io.devflow.notification.api.NotificationResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All reads and mutations are scoped by the authenticated recipient. */
@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean insert(UUID recipientId, NotificationResponse notification) {
        return jdbc.update("""
                INSERT INTO notifications
                    (id, event_id, recipient_id, workspace_id, type, title, content, reference_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, recipient_id) DO NOTHING
                """, notification.id(), notification.eventId(), recipientId, notification.workspaceId(),
                notification.type(), notification.title(), notification.content(), notification.referenceId(),
                Timestamp.from(notification.createdAt())) == 1;
    }
    public List<NotificationResponse> list(UUID recipientId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM notifications WHERE recipient_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
                """, NotificationRepository::map, recipientId, size + 1, (long) page * size);
    }
    public long unreadCount(UUID recipientId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND read_at IS NULL",
                Long.class, recipientId);
    }
    public Optional<NotificationResponse> find(UUID recipientId, UUID id) {
        return jdbc.query("SELECT * FROM notifications WHERE recipient_id = ? AND id = ?",
                NotificationRepository::map, recipientId, id).stream().findFirst();
    }
    public void markRead(UUID recipientId, UUID id, Instant now) {
        jdbc.update("UPDATE notifications SET read_at = ? WHERE recipient_id = ? AND id = ? AND read_at IS NULL",
                Timestamp.from(now), recipientId, id);
    }
    public int markAllRead(UUID recipientId, Instant now) {
        return jdbc.update("UPDATE notifications SET read_at = ? WHERE recipient_id = ? AND read_at IS NULL",
                Timestamp.from(now), recipientId);
    }
    public int deleteBatch(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM notifications WHERE id IN
                    (SELECT id FROM notifications WHERE created_at < ? ORDER BY created_at, id LIMIT ?)
                """, Timestamp.from(cutoff), limit);
    }
    private static NotificationResponse map(ResultSet rs, int row) throws SQLException {
        Timestamp readAt = rs.getTimestamp("read_at");
        return new NotificationResponse(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("workspace_id", UUID.class), rs.getString("type"), rs.getString("title"),
                rs.getString("content"), rs.getObject("reference_id", UUID.class), readAt != null,
                rs.getTimestamp("created_at").toInstant(), readAt == null ? null : readAt.toInstant());
    }
}
