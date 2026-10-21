package com.boardinghousebot.config;

import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;
import static org.junit.jupiter.api.Assertions.*;

class PaymentMethodMigrationTest {
    @Test void acceptsUnspecifiedMethodAndPreservesExistingPaymentsAndIndexes() throws Exception {
        var ds=new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:file:payment-method-migration?mode=memory&cache=shared");
        try(var connection=ds.getConnection(); var stmt=connection.createStatement()) {
            stmt.execute("CREATE TABLE invoices(id INTEGER PRIMARY KEY)");
            stmt.execute("INSERT INTO invoices VALUES(1)");
            stmt.execute("CREATE TABLE payments(id INTEGER PRIMARY KEY,invoice_id INTEGER REFERENCES invoices(id),amount NUMERIC,payment_method VARCHAR(255) NOT NULL CHECK(payment_method IN ('CASH','BANK_TRANSFER')),note TEXT)");
            stmt.execute("CREATE INDEX idx_test_payment_invoice ON payments(invoice_id)");
            stmt.execute("INSERT INTO payments VALUES(1,1,4150000,'CASH','old note')");
            var migration=new DatabaseMigrationService(ds);
            migration.migratePaymentMethods(); migration.migratePaymentMethods();
            stmt.execute("INSERT INTO payments VALUES(2,1,4150000,'UNSPECIFIED','')");
            try(var rs=stmt.executeQuery("SELECT amount,payment_method,note FROM payments WHERE id=1")) {
                assertTrue(rs.next()); assertEquals(4150000,rs.getInt(1));
                assertEquals("CASH",rs.getString(2)); assertEquals("old note",rs.getString(3));
            }
            try(var rs=stmt.executeQuery("SELECT 1 FROM sqlite_master WHERE name='idx_test_payment_invoice'")) { assertTrue(rs.next()); }
            try(var rs=stmt.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rs.next()); }
            assertThrows(java.sql.SQLException.class,()->stmt.execute("INSERT INTO payments VALUES(3,1,1,'INVALID','')"));
        }
    }
}
