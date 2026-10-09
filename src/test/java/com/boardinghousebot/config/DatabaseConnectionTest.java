package com.boardinghousebot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DatabaseConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void testSQLiteDatabaseConnection() throws SQLException {
        assertNotNull(dataSource, "DataSource should be autowired");
        try (Connection connection = dataSource.getConnection()) {
            assertNotNull(connection, "Database connection should be established");
            assertTrue(connection.isValid(2), "Connection should be valid");
        }
    }
}
