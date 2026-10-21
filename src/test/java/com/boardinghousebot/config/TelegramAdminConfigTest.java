package com.boardinghousebot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TelegramAdminConfigTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "0", "-123", "+123", "abc", "123,456", "123.0", "00123", "9223372036854775808"})
    void invalidIdIsRejected(String value) {
        TelegramConfig config = new TelegramConfig();
        config.setAdminId(value);
        IllegalStateException error = assertThrows(IllegalStateException.class, config::getRequiredAdminTelegramId);
        assertTrue(error.getMessage().contains("TELEGRAM_ADMIN_ID"));
    }

    @Test
    void acceptsWhitespaceAndIdsLargerThanInteger() {
        TelegramConfig config = new TelegramConfig();
        config.setAdminId(" 6849481889 ");
        assertEquals(6849481889L, config.getRequiredAdminTelegramId());
        assertTrue(config.isConfiguredAdmin(6849481889L));
        assertFalse(config.isConfiguredAdmin(null));
        assertFalse(config.isConfiguredAdmin(1L));
    }
}
