package com.boardinghousebot.util;

import org.telegram.telegrambots.meta.api.objects.Update;

/** Extracts message and callback data consistently across command handlers. */
public final class TelegramUpdateUtil {

    private TelegramUpdateUtil() {
    }

    public static Long extractChatId(Update update) {
        if (update.hasMessage()) return update.getMessage().getChatId();
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }

    public static String extractText(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            return update.getMessage().getText();
        }
        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getData();
        }
        return null;
    }
}
