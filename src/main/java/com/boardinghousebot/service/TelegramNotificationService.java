package com.boardinghousebot.service;

import com.boardinghousebot.bot.command.BotMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class TelegramNotificationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramNotificationService.class);

    private final BotMessageSender botMessageSender;

    public TelegramNotificationService(@Lazy BotMessageSender botMessageSender) {
        this.botMessageSender = botMessageSender;
    }

    public void sendNotification(Long telegramId, String messageText) {
        if (telegramId == null || messageText == null || messageText.isBlank()) {
            return;
        }

        try {
            log.info("Sending immediate Telegram notification to Telegram ID {}: {}", telegramId, messageText.replace("\n", " "));
            botMessageSender.sendMessage(telegramId, messageText);
        } catch (Exception e) {
            log.error("Failed to send immediate Telegram notification to Telegram ID {}: {}", telegramId, e.getMessage(), e);
            // Catch error silently so main business logic and DB notification remain saved without rollback
        }
    }
}
