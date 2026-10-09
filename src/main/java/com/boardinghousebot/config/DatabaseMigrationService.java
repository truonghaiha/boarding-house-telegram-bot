package com.boardinghousebot.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class DatabaseMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationService.class);
    private final DataSource dataSource;

    public DatabaseMigrationService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    public void migrateOnStartup() {
        migrateSchemaIfNeeded();
    }

    /**
     * Checks if the SQLite notifications table requires migration for new NotificationTypes.
     * Performs a safe, transactional SQLite table migration if needed.
     *
     * @return true if migration was performed, false if schema was already up-to-date or skipped.
     */
    public boolean migrateSchemaIfNeeded() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Check if notifications table exists
            ResultSet rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='notifications'");
            if (!rs.next()) {
                // Table does not exist (e.g. in-memory DB before JPA initialization)
                return false;
            }

            String sql = rs.getString("sql");
            if (isSchemaUpToDate(sql)) {
                log.info("Database schema for notifications table is up-to-date. Migration skipped.");
                return false;
            }

            log.info("Outdated CHECK constraint detected on notifications table. Starting safe SQLite migration...");

            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try {
                stmt.execute("PRAGMA foreign_keys=OFF;");

                // Create new notifications table with updated CHECK constraint containing all 11 NotificationType values
                stmt.execute("""
                    CREATE TABLE notifications_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at TIMESTAMP NOT NULL,
                        is_read BOOLEAN NOT NULL,
                        message TEXT NOT NULL,
                        reference_id BIGINT,
                        reference_type VARCHAR(255),
                        title VARCHAR(255) NOT NULL,
                        type VARCHAR(50) NOT NULL CHECK (type IN (
                            'MAINTENANCE_CREATED',
                            'MAINTENANCE_STATUS_CHANGED',
                            'ROLE_CHANGED',
                            'ROOM_CHANGED',
                            'INVOICE_CREATED',
                            'INVOICE_DUE_REMINDER',
                            'INVOICE_OVERDUE',
                            'CONTRACT_EXPIRING',
                            'CONTRACT_CREATED',
                            'ELECTRICITY_CREATED',
                            'WATER_CREATED',
                            'PAYMENT_RECORDED'
                        )),
                        recipient_id BIGINT NOT NULL,
                        FOREIGN KEY (recipient_id) REFERENCES users(id)
                    );
                """);

                // Copy all existing rows from old table to new table
                stmt.execute("""
                    INSERT INTO notifications_new (id, created_at, is_read, message, reference_id, reference_type, title, type, recipient_id)
                    SELECT id, created_at, is_read, message, reference_id, reference_type, title, type, recipient_id
                    FROM notifications;
                """);

                // Drop old table
                stmt.execute("DROP TABLE notifications;");

                // Rename new table to notifications
                stmt.execute("ALTER TABLE notifications_new RENAME TO notifications;");

                // Recreate indexes
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_notif_recipient ON notifications(recipient_id);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_notif_is_read ON notifications(is_read);");

                stmt.execute("PRAGMA foreign_keys=ON;");

                conn.commit();
                log.info("Successfully migrated notifications table schema with all 11 NotificationType values.");
                return true;

            } catch (Exception e) {
                conn.rollback();
                log.error("Failed to migrate notifications table schema. Rolled back changes.", e);
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }

        } catch (Exception e) {
            log.warn("Database schema migration check completed with message: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Determines whether the CREATE TABLE SQL of 'notifications' allows all 11 NotificationTypes.
     */
    public boolean isSchemaUpToDate(String createTableSql) {
        if (createTableSql == null || createTableSql.trim().isEmpty()) {
            return true;
        }
        String uppercaseSql = createTableSql.toUpperCase();
        // If there is no CHECK constraint on the table, SQLite does not restrict NotificationType values.
        if (!uppercaseSql.contains("CHECK")) {
            return true;
        }
        // If CHECK constraint exists, verify it contains the 3 new types (CONTRACT_CREATED, ELECTRICITY_CREATED, WATER_CREATED)
        return createTableSql.contains("CONTRACT_CREATED")
                && createTableSql.contains("ELECTRICITY_CREATED")
                && createTableSql.contains("WATER_CREATED")
                && createTableSql.contains("PAYMENT_RECORDED");
    }
}
