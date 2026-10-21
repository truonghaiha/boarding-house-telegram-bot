package com.boardinghousebot.config;

import com.boardinghousebot.BoardingHouseBotApplication;
import com.boardinghousebot.bot.BoardingHouseTelegramBot;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class AdminStartupIntegrationTest {
    @TempDir Path directory;
    static final long OLD_ID = 88100001L;
    static final long NEW_ID = 88100002L;

    ApplicationContextRunner runner(String adminId) {
        return new ApplicationContextRunner().withUserConfiguration(BoardingHouseBotApplication.class)
                .withPropertyValues("spring.config.import=", "telegram.bot.token=", "telegram.bot.username=test_bot",
                        "telegram.admin-id=" + adminId,
                        "spring.datasource.url=jdbc:sqlite:" + directory.resolve("startup.db"),
                        "spring.datasource.driver-class-name=org.sqlite.JDBC",
                        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
                        "spring.jpa.hibernate.ddl-auto=update", "logging.level.root=ERROR");
    }

    void seed(boolean renting) {
        runner(Long.toString(OLD_ID)).run(context -> {
            assertThat(context).hasNotFailed();
            UserRepository users = context.getBean(UserRepository.class);
            users.save(new User(OLD_ID, "old", "Old Admin", Role.ADMIN));
            User next = new User(NEW_ID, "next", "Next Admin", Role.MEMBER);
            if (renting) {
                Room room = context.getBean(RoomRepository.class)
                        .save(new Room("STARTUP-ROOM", BigDecimal.TEN, 2, ""));
                next.setRoom(room);
            }
            users.save(next);
        });
    }

    @Test void restartTransfersPersistedRolesBeforeBotIsAvailable() {
        seed(false);
        runner(Long.toString(NEW_ID)).run(context -> {
            assertThat(context).hasNotFailed();
            assertNotNull(context.getBean(BoardingHouseTelegramBot.class));
            UserRepository users = context.getBean(UserRepository.class);
            assertEquals(Role.MEMBER, users.findByTelegramId(OLD_ID).orElseThrow().getRole());
            assertEquals(Role.ADMIN, users.findByTelegramId(NEW_ID).orElseThrow().getRole());
        });
    }

    @Test void invalidConfigurationStopsStartupAndPreservesPersistedRoles() throws Exception {
        seed(false);
        runner("").run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("TELEGRAM_ADMIN_ID"));
        assertPersistedRolesUnchanged();
    }

    @Test void tenantConfiguredAsAdminStopsStartupAndPreservesPersistedRoles() throws Exception {
        seed(true);
        runner(Long.toString(NEW_ID)).run(context -> assertThat(context).hasFailed()
                .getFailure().hasRootCauseInstanceOf(com.boardinghousebot.exception.BusinessException.class));
        assertPersistedRolesUnchanged();
    }

    void assertPersistedRolesUnchanged() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("startup.db"));
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT telegram_id, role FROM users ORDER BY telegram_id")) {
            assertTrue(result.next());
            assertEquals(OLD_ID, result.getLong("telegram_id"));
            assertEquals("ADMIN", result.getString("role"));
            assertTrue(result.next());
            assertEquals(NEW_ID, result.getLong("telegram_id"));
            assertEquals("MEMBER", result.getString("role"));
            assertFalse(result.next());
        }
    }
}
