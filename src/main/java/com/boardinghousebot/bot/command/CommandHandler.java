package com.boardinghousebot.bot.command;

import com.boardinghousebot.domain.user.User;
import org.telegram.telegrambots.meta.api.objects.Update;

public interface CommandHandler {

    String getCommand();

    void handle(Update update, User user, BotMessageSender messageSender);
}
