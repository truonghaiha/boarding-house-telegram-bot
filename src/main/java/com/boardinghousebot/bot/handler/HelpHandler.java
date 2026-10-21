package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class HelpHandler implements CommandHandler {

    @Override
    public String getCommand() {
        return "/help";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        Long chatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();

        String helpText = """
                ℹ️ HƯỚNG DẪN SỬ DỤNG BOARDING HOUSE BOT

                Các lệnh cơ bản:
                • /start - Khởi động bot và mở menu chính
                • /menu  - Hiển thị menu chức năng theo vai trò
                • /help  - Mở trang trợ giúp này

                Lưu ý:
                • ADMIN: quản lý tài khoản, phân quyền, nhật ký và thống kê hệ thống.
                • LANDLORD: cùng quản lý hoạt động của một nhà trọ; ghi nhận thanh toán.
                • MEMBER: xem phòng còn chỗ ngay khi vào bot; xem thông tin thuê và báo sự cố khi đã thuê.
                • Lịch sử hóa đơn/điện nước được giới hạn theo thời gian cư trú ghi nhận.
                • Cần kết thúc hợp đồng và rời phòng trước khi cấp LANDLORD cho người thuê.
                • Nếu cần hỗ trợ trực tiếp, vui lòng liên hệ Quản trị viên hệ thống.
                """;

        messageSender.sendMessageWithKeyboard(chatId, helpText, TelegramKeyboardUtil.getRoleMenu(user.getRole()));
    }
}
