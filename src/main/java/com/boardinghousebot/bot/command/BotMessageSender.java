package com.boardinghousebot.bot.command;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

public interface BotMessageSender {
    void sendMessage(Long chatId, String text);
    void sendMessageWithKeyboard(Long chatId, String text, InlineKeyboardMarkup keyboard);
}
