package com.boardinghousebot.config;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
@Getter
@Setter
public class TelegramConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramConfig.class);

    @Value("${telegram.bot.username:}")
    private String botUsername;

    @Value("${telegram.bot.token:}")
    private String botToken;

    @Value("${telegram.admin-id:}")
    private String adminId;

    public Long getRequiredAdminTelegramId() {
        String value = adminId == null ? "" : adminId.trim();
        if (value.matches("[1-9][0-9]*")) {
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                // Treat overflow as invalid configuration as well.
            }
        }
        throw new IllegalStateException("TELEGRAM_ADMIN_ID phải là một Telegram ID nguyên dương hợp lệ. Kiểm tra .env rồi khởi động lại bot.");
    }

    public boolean isConfiguredAdmin(Long telegramId) {
        return getRequiredAdminTelegramId().equals(telegramId);
    }

    public void logConfigurationStatus() {
        log.info("Telegram Bot Configured - Username: {}, Admin ID: {}, Token Provided: {}",
                botUsername, adminId, (botToken != null && !botToken.isBlank()));
    }
}
