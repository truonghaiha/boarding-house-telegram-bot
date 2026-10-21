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
        migrateInvoiceCycleIndexes();
        migratePaymentMethods();
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

                // Create a notifications table that accepts all current notification types.
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
                            'CONTRACT_ENDED',
                            'ELECTRICITY_CREATED',
                            'WATER_CREATED',
                            'PAYMENT_RECORDED',
                            'UTILITY_RATE_CHANGED',
                            'UTILITY_CYCLE_FINALIZED'
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
                log.info("Successfully migrated notifications table schema with current NotificationType values.");
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
     * Determines whether the CREATE TABLE SQL of 'notifications' allows current NotificationTypes.
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
        // Tables with a CHECK constraint must accept the newer notification types.
        return createTableSql.contains("CONTRACT_CREATED")
                && createTableSql.contains("CONTRACT_ENDED")
                && createTableSql.contains("ELECTRICITY_CREATED")
                && createTableSql.contains("WATER_CREATED")
                && createTableSql.contains("PAYMENT_RECORDED")
                && createTableSql.contains("UTILITY_RATE_CHANGED")
                && createTableSql.contains("UTILITY_CYCLE_FINALIZED");
    }
    public void migratePaymentMethods() {
        try(var conn=dataSource.getConnection(); var stmt=conn.createStatement()) {
            String ddl;
            try(var rs=stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='payments'")) {
                if(!rs.next()) return;
                ddl=rs.getString(1);
            }
            String updated=ddl.replaceAll("(?i)(payment_method\\s+in\\s*\\()([^)]*)(\\))", "$1$2,'UNSPECIFIED'$3");
            if(ddl.contains("UNSPECIFIED") || updated.equals(ddl)) return;
            boolean foreignKeys;
            try(var rs=stmt.executeQuery("PRAGMA foreign_keys")) { foreignKeys=rs.next() && rs.getInt(1)!=0; }
            stmt.execute("PRAGMA foreign_keys=OFF");
            conn.setAutoCommit(false);
            try {
                var columns=new java.util.ArrayList<String>();
                try(var rs=stmt.executeQuery("PRAGMA table_info(payments)")) {
                    while(rs.next()) columns.add("\""+rs.getString("name")+"\"");
                }
                var definitions=new java.util.ArrayList<String>();
                try(var rs=stmt.executeQuery("SELECT sql FROM sqlite_master WHERE tbl_name='payments' AND type IN ('index','trigger') AND sql IS NOT NULL")) {
                    while(rs.next()) definitions.add(rs.getString(1));
                }
                stmt.execute("CREATE TABLE payments_method_migration "+updated.substring(updated.indexOf('(')));
                String names=String.join(",",columns);
                stmt.execute("INSERT INTO payments_method_migration ("+names+") SELECT "+names+" FROM payments");
                stmt.execute("DROP TABLE payments");
                stmt.execute("ALTER TABLE payments_method_migration RENAME TO payments");
                for(var definition:definitions) stmt.execute(definition);
                try(var rs=stmt.executeQuery("PRAGMA foreign_key_check")) {
                    if(rs.next()) throw new IllegalStateException("Foreign key validation failed after payment migration");
                }
                conn.commit();
            } catch(Exception e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); stmt.execute("PRAGMA foreign_keys="+(foreignKeys?"ON":"OFF")); }
        } catch(Exception e) { throw new IllegalStateException("Không thể cập nhật phương thức thanh toán; dữ liệu được giữ nguyên.",e); }
    }

    public void migrateInvoiceCycleIndexes() {
        try (Connection conn=dataSource.getConnection(); Statement stmt=conn.createStatement()) {
            var columns=new java.util.ArrayList<String>();
            try (var rs=stmt.executeQuery("PRAGMA table_info(invoices)")) {
                while(rs.next()) columns.add(rs.getString("name"));
            }
            if (columns.isEmpty()) return; // A fresh database is created by Hibernate.
            boolean foreignKeys;
            try (var rs=stmt.executeQuery("PRAGMA foreign_keys")) { foreignKeys=rs.next() && rs.getInt(1)!=0; }
            if (!conn.getAutoCommit()) throw new IllegalStateException("Migration requires its own transaction");
            stmt.execute("PRAGMA foreign_keys=OFF");
            conn.setAutoCommit(false);
            try {
                // SQLite cannot add a column with a UNIQUE constraint via Hibernate's
                // schema update. Add nullable columns first, then create the indexes.
                var cycleColumns = new java.util.LinkedHashMap<String, String>();
                cycleColumns.put("utility_cycle_id", "BIGINT");
                cycleColumns.put("period_start", "DATE");
                cycleColumns.put("period_end", "DATE");
                cycleColumns.put("note", "VARCHAR(255)");
                cycleColumns.put("checkout_date", "DATE");
                for (var column : cycleColumns.entrySet()) {
                    if (!columns.contains(column.getKey())) {
                        stmt.execute("ALTER TABLE invoices ADD COLUMN " + column.getKey() + " " + column.getValue());
                        columns.add(column.getKey());
                    }
                }
                String ddl;
                try (var rs=stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='invoices'")) {
                    if (!rs.next()) throw new IllegalStateException("Invoice table disappeared during migration");
                    ddl=rs.getString(1);
                }
                String updated=ddl.replaceAll("(?i),?\\s*(?:constraint\\s+\\w+\\s+)?unique\\s*\\(\\s*room_id\\s*,\\s*billing_month\\s*,\\s*billing_year\\s*\\)", "");
                stmt.execute("DROP INDEX IF EXISTS idx_invoice_room_period_unique");
                stmt.execute("DROP INDEX IF EXISTS uk_invoice_room_period");
                if (!ddl.equals(updated)) {
                    var indexes=new java.util.ArrayList<String>();
                    try (var rs=stmt.executeQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='invoices' AND sql IS NOT NULL")) {
                        while(rs.next()) indexes.add(rs.getString(1));
                    }
                    String create="CREATE TABLE invoices_cycle_migration " + updated.substring(updated.indexOf('('));
                    stmt.execute(create);
                    String cols=columns.stream().map(c -> "\""+c+"\"").collect(java.util.stream.Collectors.joining(","));
                    stmt.execute("INSERT INTO invoices_cycle_migration ("+cols+") SELECT "+cols+" FROM invoices");
                    stmt.execute("DROP TABLE invoices");
                    stmt.execute("ALTER TABLE invoices_cycle_migration RENAME TO invoices");
                    for (String index:indexes) stmt.execute(index);
                }
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_invoice_legacy_period_unique ON invoices(room_id,billing_month,billing_year) WHERE utility_cycle_id IS NULL");
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_invoice_cycle_unique ON invoices(utility_cycle_id) WHERE utility_cycle_id IS NOT NULL");
                try (var rs=stmt.executeQuery("PRAGMA foreign_key_check")) {
                    if (rs.next()) throw new IllegalStateException("Foreign key validation failed after invoice migration");
                }
                conn.commit();
            } catch(Exception e) { conn.rollback(); throw e; }
            finally { conn.setAutoCommit(true); stmt.execute("PRAGMA foreign_keys="+(foreignKeys?"ON":"OFF")); }
        } catch(Exception e) { throw new IllegalStateException("Không thể cập nhật cấu trúc kỳ hóa đơn; dữ liệu được giữ nguyên.",e); }
    }

}
