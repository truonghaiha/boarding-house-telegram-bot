package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class StartHandler implements CommandHandler {

    @Override
    public String getCommand() {
        return "/start";
    }

    @Override
    public String getDescription() {
        return "Bắt đầu sử dụng bot và xem menu chức năng";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        Long chatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();

        StringBuilder welcomeMsg = new StringBuilder();
        if (user.getRole() == Role.ADMIN) {
            welcomeMsg.append("👑 ADMIN PANEL\n\n");
            welcomeMsg.append("Chào mừng Quản trị viên ").append(user.getFullName()).append("!\n");
            welcomeMsg.append("Vui lòng chọn chức năng quản trị bên dưới:");
        } else if (user.getRole() == Role.LANDLORD) {
            welcomeMsg.append("🏠 QUẢN LÝ NHÀ TRỌ\n\n");
            welcomeMsg.append("Chào mừng Chủ nhà ").append(user.getFullName()).append("!\n");
            welcomeMsg.append("🔑 Vai trò: LANDLORD\n\n");
            welcomeMsg.append("Vui lòng chọn chức năng quản lý:");
        } else {
            welcomeMsg.append("👋 Chào mừng bạn đến với Boarding House Bot!\n\n");
            welcomeMsg.append("👤 Tài khoản: ").append(user.getFullName()).append("\n");
            welcomeMsg.append("🔑 Vai trò: MEMBER\n\n");
            welcomeMsg.append("Vui lòng chọn chức năng bên dưới:");
        }

        messageSender.sendMessageWithKeyboard(chatId, welcomeMsg.toString(), TelegramKeyboardUtil.getRoleMenu(user.getRole()));
    }
}
