package com.boardinghousebot.bot.command;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import com.boardinghousebot.util.TelegramKeyboardUtil;

public interface BotMessageSender {
    void sendMessage(Long chatId, String text);
    void sendMessageWithKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard);

    /** A visible notice is always a new chat message; callback acknowledgements are separate. */
    default void sendNotice(Long chatId, String text) {
        sendMessage(chatId, text);
    }

    /** Preserve the business receipt in history, then send the corresponding menu. */
    default void sendResultAndMenu(Long chatId, String result, InlineKeyboardMarkup keyboard) {
        sendMessage(chatId, result);
        sendMessageWithKeyboard(chatId, "📌 Chọn thao tác tiếp theo:", keyboard);
    }

    /** Input prompts are separate from navigation screens. */
    default void sendPrompt(Long chatId, String text) {
        sendPrompt(chatId, text, TelegramKeyboardUtil.getCancelKeyboard());
    }

    default void sendOptionalPrompt(Long chatId, String text, com.boardinghousebot.state.UserConversationState.Step step) {
        sendPrompt(chatId, text, TelegramKeyboardUtil.getOptionalInputKeyboard(step));
    }

    default void sendPrompt(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        sendMessageWithKeyboard(chatId, text, keyboard);
    }

    /** Compatibility API: always send new plain text; the message ID is not used for editing. */
    default void sendOrEditMessageWithKeyboard(Long chatId, Integer messageId, String text, InlineKeyboardMarkup keyboard) {
        sendMessageWithKeyboard(chatId, text, keyboard);
    }
}
