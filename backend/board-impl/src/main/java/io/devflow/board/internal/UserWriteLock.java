package io.devflow.board.internal;

import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-user PostgreSQL advisory lock, held until the current transaction ends (T-009).
 *
 * <p>Every Board read or write on behalf of a user takes it shared, at its membership check; account-deletion
 * cleanup takes it exclusively. Cleanup therefore waits for writes that passed their membership check before
 * the account was deleted, and writes that start afterwards fail that check. Without a transaction the lock is
 * released at the end of the statement, which only waits out a running cleanup.
 *
 * <p>Other databases (H2 in tests) have no advisory locks; there it does nothing.
 */
@Component
class UserWriteLock {

    /** Namespace of the two-key advisory lock form, so these keys cannot collide with other advisory locks. */
    static final int LOCK_CLASS = 0x7009;

    private static final Logger log = LoggerFactory.getLogger(UserWriteLock.class);

    private final JdbcTemplate jdbc;
    private volatile Boolean supported;

    UserWriteLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lockShared(UUID userId) {
        lock("select pg_advisory_xact_lock_shared(?, ?)", userId);
    }

    void lockExclusive(UUID userId) {
        lock("select pg_advisory_xact_lock(?, ?)", userId);
    }

    private void lock(String sql, UUID userId) {
        if (userId != null && isSupported()) {
            jdbc.query(sql, resultSet -> null, LOCK_CLASS, userId.hashCode());
        }
    }

    private boolean isSupported() {
        Boolean value = supported;
        if (value == null) {
            String product = jdbc.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName());
            value = product != null && product.toLowerCase(Locale.ROOT).contains("postgres");
            if (!value) {
                log.warn("Per-user write lock disabled: {} has no advisory locks", product);
            }
            supported = value;
        }
        return value;
    }
}
