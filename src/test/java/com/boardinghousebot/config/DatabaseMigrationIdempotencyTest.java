package com.boardinghousebot.config;

import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.NotificationRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DatabaseMigrationIdempotencyTest {

    @Autowired
    private DatabaseMigrationService migrationService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("CASE 1 & CASE 2: Verify isSchemaUpToDate logic and idempotency when schema is already updated")
    void testSchemaCheckLogicAndIdempotency() {
        // CASE 1: Old schema with only 8 types must be identified as outdated
        String oldSchemaSql = """
            CREATE TABLE notifications (
                id integer,
                type VARCHAR(50) not null check (type in ('MAINTENANCE_CREATED','MAINTENANCE_STATUS_CHANGED','ROLE_CHANGED','ROOM_CHANGED','INVOICE_CREATED','INVOICE_DUE_REMINDER','INVOICE_OVERDUE','CONTRACT_EXPIRING')),
                recipient_id bigint not null
            )
        """;
        assertFalse(migrationService.isSchemaUpToDate(oldSchemaSql),
                "Old schema with 8 NotificationTypes must return false (requires migration)");

        // CASE 2: Schema with all 12 types must be identified as up-to-date
        String newSchemaSql = """
            CREATE TABLE notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type VARCHAR(50) NOT NULL CHECK (type IN ('MAINTENANCE_CREATED','MAINTENANCE_STATUS_CHANGED','ROLE_CHANGED','ROOM_CHANGED','INVOICE_CREATED','INVOICE_DUE_REMINDER','INVOICE_OVERDUE','CONTRACT_EXPIRING','CONTRACT_CREATED','ELECTRICITY_CREATED','WATER_CREATED','PAYMENT_RECORDED')),
                recipient_id BIGINT NOT NULL
            )
        """;
        assertTrue(migrationService.isSchemaUpToDate(newSchemaSql),
                "Schema with all NotificationTypes must return true (up to date)");

        // Schema without CHECK constraint is also up-to-date
        String noCheckSchemaSql = "CREATE TABLE notifications (id INTEGER, type VARCHAR(50))";
        assertTrue(migrationService.isSchemaUpToDate(noCheckSchemaSql),
                "Schema without CHECK constraint must return true");

        // Executing migrationService.migrateSchemaIfNeeded() on current up-to-date DB must return false (idempotent / skipped)
        boolean ranSecond = migrationService.migrateSchemaIfNeeded();
        assertFalse(ranSecond, "migrateSchemaIfNeeded() must return false (skipped) on up-to-date database");
    }

    @Test
    @DisplayName("CASE 3 & CASE 4: Verify all NotificationTypes can be inserted and read back successfully")
    void testAll11NotificationTypesCanBeInsertedAndPreserved() throws Exception {
        // Ensure migration service has run
        migrationService.migrateSchemaIfNeeded();

        User user = new User();
        user.setTelegramId(8877665544L);
        user.setFullName("Idempotency Test User");
        user.setRole(Role.MEMBER);
        userRepository.save(user);

        // CASE 3: Test inserting all NotificationTypes
        for (NotificationType type : NotificationType.values()) {
            Notification notif = new Notification(
                    user,
                    type,
                    "Title " + type.name(),
                    "Message for " + type.name(),
                    "REF",
                    100L
            );
            assertDoesNotThrow(() -> {
                notificationRepository.save(notif);
                notificationRepository.flush();
            }, "Database must allow inserting NotificationType: " + type.name());
        }

        // CASE 4: Read back and verify all records are preserved
        List<Notification> saved = notificationRepository.findByRecipientTelegramIdOrderByCreatedAtDesc(8877665544L);
        assertEquals(NotificationType.values().length, saved.size(), "All NotificationTypes must be present and preserved in database");

        // Verify SQLite table definition directly in sqlite_master
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='notifications'");
            if (rs.next()) {
                String sql = rs.getString("sql");
                assertTrue(migrationService.isSchemaUpToDate(sql), "sqlite_master CREATE TABLE SQL must be up to date");
            }
        }
    }
}
