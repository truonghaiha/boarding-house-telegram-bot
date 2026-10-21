package com.boardinghousebot.config;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;
class InvoiceCycleMigrationTest {
    @Test void upgradesLegacyTableWithoutCycleColumnsAndPreservesData() throws Exception {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:file:invoice-cycle-legacy?mode=memory&cache=shared");
        try (var keep = ds.getConnection(); var stmt = keep.createStatement()) {
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute("CREATE TABLE invoices(id INTEGER PRIMARY KEY,room_id BIGINT,billing_month INTEGER,billing_year INTEGER,paid_amount NUMERIC,CONSTRAINT uk_invoice_room_period UNIQUE(room_id,billing_month,billing_year))");
            stmt.execute("CREATE TABLE payments(id INTEGER PRIMARY KEY,invoice_id BIGINT REFERENCES invoices(id),amount NUMERIC)");
            stmt.execute("INSERT INTO invoices VALUES(1,101,10,2026,1500000)");
            stmt.execute("INSERT INTO payments VALUES(1,1,1500000)");

            var migration = new DatabaseMigrationService(ds);
            migration.migrateOnStartup();
            migration.migrateOnStartup();

            try (var rs = stmt.executeQuery("SELECT utility_cycle_id,period_start,period_end,paid_amount FROM invoices WHERE id=1")) {
                assertTrue(rs.next());
                assertNull(rs.getObject("utility_cycle_id"));
                assertNull(rs.getObject("period_start"));
                assertNull(rs.getObject("period_end"));
                assertEquals(1500000, rs.getInt("paid_amount"));
            }
            try (var rs = stmt.executeQuery("SELECT invoice_id,amount FROM payments WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("invoice_id"));
                assertEquals(1500000, rs.getInt("amount"));
            }
            assertThrows(java.sql.SQLException.class, () -> stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,paid_amount) VALUES(2,101,10,2026,0)"));
            stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,paid_amount,utility_cycle_id,period_start,period_end) VALUES(3,101,10,2026,0,30,'2026-10-01','2026-10-15')");
            stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,paid_amount,utility_cycle_id,period_start,period_end) VALUES(4,101,10,2026,0,31,'2026-10-15','2026-10-20')");
            assertThrows(java.sql.SQLException.class, () -> stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,paid_amount,utility_cycle_id) VALUES(5,102,11,2026,0,30)"));
            try (var rs = stmt.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rs.next()); }
        }
    }

    @Test void upgradesEmbeddedUniqueConstraintPreservesPaymentsAndIsIdempotent() throws Exception {
        SQLiteDataSource ds=new SQLiteDataSource(); ds.setUrl("jdbc:sqlite:file:invoice-cycle-migration?mode=memory&cache=shared");
        try(var keep=ds.getConnection();var stmt=keep.createStatement()) {
            stmt.execute("PRAGMA foreign_keys=ON");
            stmt.execute("CREATE TABLE invoices(id INTEGER PRIMARY KEY,room_id BIGINT,billing_month INTEGER,billing_year INTEGER,utility_cycle_id BIGINT,paid_amount NUMERIC,CONSTRAINT uk_invoice_room_period UNIQUE(room_id,billing_month,billing_year))");
            stmt.execute("CREATE UNIQUE INDEX idx_invoice_room_period_unique ON invoices(room_id,billing_month,billing_year)");
            stmt.execute("CREATE TABLE payments(id INTEGER PRIMARY KEY,invoice_id BIGINT REFERENCES invoices(id),amount NUMERIC)");
            stmt.execute("INSERT INTO invoices VALUES(1,101,10,2026,NULL,1500000)");
            stmt.execute("INSERT INTO payments VALUES(1,1,1500000)");
            var migration=new DatabaseMigrationService(ds);
            migration.migrateInvoiceCycleIndexes(); migration.migrateInvoiceCycleIndexes();
            try(var rs=stmt.executeQuery("SELECT paid_amount FROM invoices WHERE id=1")) { assertTrue(rs.next()); assertEquals(1500000,rs.getInt(1)); }
            try(var rs=stmt.executeQuery("SELECT invoice_id FROM payments WHERE id=1")) { assertTrue(rs.next()); assertEquals(1,rs.getInt(1)); }
            assertThrows(java.sql.SQLException.class,()->stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,utility_cycle_id,paid_amount) VALUES(2,101,10,2026,NULL,0)"));
            stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,utility_cycle_id,paid_amount) VALUES(3,101,10,2026,30,0)");
            stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,utility_cycle_id,paid_amount) VALUES(4,101,10,2026,31,0)");
            assertThrows(java.sql.SQLException.class,()->stmt.execute("INSERT INTO invoices(id,room_id,billing_month,billing_year,utility_cycle_id,paid_amount) VALUES(5,102,11,2026,30,0)"));
            try(var rs=stmt.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rs.next()); }
        }
    }
}
