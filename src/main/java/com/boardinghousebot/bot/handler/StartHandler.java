package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.config.BoardingHouseContactConfig;
import com.boardinghousebot.service.RoomStayService;
import com.boardinghousebot.service.RoomStayService.CurrentResidence;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.format.DateTimeFormatter;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Component
public class StartHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(StartHandler.class);
    public static final String STATUS_ERROR = "⚠️ Không thể kiểm tra trạng thái thuê phòng lúc này. Vui lòng thử lại sau.";
    private final RoomStayService roomStayService;
    private final BoardingHouseContactConfig contact;

    public StartHandler(RoomStayService roomStayService, BoardingHouseContactConfig contact) {
        this.roomStayService = roomStayService;
        this.contact = contact;
    }

    @Override
    public String getCommand() {
        return "/start";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        if (user.getRole() == Role.MEMBER) {
            showMemberMenu(update, user, messageSender, true);
            return;
        }
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

    public void showMemberMenu(Update update, User user, BotMessageSender sender, boolean welcome) {
        try {
            CurrentResidence residence = roomStayService.getCurrentResidence(user.getTelegramId());
            if (residence.isRenting()) {
                String text = welcome ? "👋 Chào mừng bạn đến với Boarding House Bot!\n\n👤 Tài khoản: "
                        + displayName(user) + "\n🔑 Vai trò: MEMBER\n\nVui lòng chọn chức năng bên dưới:" : "📌 MENU CHÍNH";
                render(update, sender, text, true);
            } else {
                String text = welcome ? "👋 CHÀO MỪNG ĐẾN VỚI BOARDING HOUSE BOT!\n\nXin chào, "
                        + displayName(user) + "!\n\n✅ Tài khoản của bạn đã được ghi nhận trong hệ thống.\n\n"
                        + "🏠 TRẠNG THÁI THUÊ PHÒNG\n\n"
                        + (residence.unconfirmed() ? "Thông tin cư trú của bạn chưa được xác nhận. Vui lòng liên hệ chủ trọ để kiểm tra."
                            : "Bạn hiện chưa được ghi nhận là người thuê phòng tại nhà trọ.")
                        + "\n\nVui lòng liên hệ trực tiếp với chủ trọ để được hỗ trợ.\nBạn có thể xem Phòng còn chỗ bên dưới."
                        + "\n\nSau khi được chủ trọ xác nhận và xếp phòng, bạn có thể sử dụng các chức năng quản lý phòng trọ."
                        : statusText(residence);
                render(update, sender, text, false);
            }
        } catch (DataAccessException e) {
            reportStatusError(update, sender, e);
        }
    }

    public void checkMemberStatus(Update update, User user, BotMessageSender sender) {
        try {
            CurrentResidence residence = roomStayService.getCurrentResidence(user.getTelegramId());
            String text = residence.isRenting() ? "✅ TRẠNG THÁI ĐÃ ĐƯỢC CẬP NHẬT!\n\n"
                    + "Bạn đã được ghi nhận là người thuê phòng " + residence.room().getRoomNumber()
                    + ".\n\nChào mừng bạn đến với nhà trọ!\n\nVui lòng chọn chức năng bên dưới:" : statusText(residence);
            render(update, sender, text, residence.isRenting());
        } catch (DataAccessException e) {
            reportStatusError(update, sender, e);
        }
    }

    public void showMemberAccount(Update update, User user, BotMessageSender sender) {
        try {
            CurrentResidence residence = roomStayService.getCurrentResidence(user.getTelegramId());
            String created = user.getCreatedAt() == null ? "N/A"
                    : user.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
            String text = "👤 THÔNG TIN TÀI KHOẢN\n\nID: " + user.getTelegramId()
                    + "\nUsername: " + optionalText(user.getUsername(), "Chưa đặt")
                    + "\nHọ tên: " + displayName(user) + "\nSố điện thoại: " + user.getPhoneNumberDisplay() + "\nNgày tham gia: " + created
                    + "\n\n🏠 TRẠNG THÁI THUÊ PHÒNG\n\n" + residence.displayStatus();
            render(update, sender, text, residence.isRenting());
        } catch (DataAccessException e) {
            reportStatusError(update, sender, e);
        }
    }

    public void showMemberContact(Update update, User user, BotMessageSender sender) {
        try {
            CurrentResidence residence = roomStayService.getCurrentResidence(user.getTelegramId());
            render(update, sender, contact.displayContact(), residence.isRenting());
        } catch (DataAccessException e) {
            reportStatusError(update, sender, e);
        }
    }

    private String statusText(CurrentResidence residence) {
        return "🏠 TRẠNG THÁI THUÊ PHÒNG\n\n"
                + (residence.unconfirmed() ? "Thông tin cư trú của bạn chưa được xác nhận."
                    : "Bạn hiện chưa được xếp vào phòng nào.")
                + "\n\nVui lòng liên hệ chủ trọ để được hỗ trợ.\n\n"
                + "🔄 Bạn có thể kiểm tra lại sau khi chủ trọ cập nhật thông tin.";
    }

    private void render(Update update, BotMessageSender sender, String text, boolean renting) {
        Long chatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        Integer messageId = update.hasCallbackQuery() ? update.getCallbackQuery().getMessage().getMessageId() : null;
        sender.sendOrEditMessageWithKeyboard(chatId, messageId, text,
                renting ? TelegramKeyboardUtil.getMemberMenu() : TelegramKeyboardUtil.getUnassignedMemberMenu());
    }

    private void reportStatusError(Update update, BotMessageSender sender, DataAccessException error) {
        log.error("Cannot query member residence", error);
        Long chatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        // Preserve the current message/menu when the database is unavailable.
        sender.sendMessage(chatId, STATUS_ERROR);
    }

    private String displayName(User user) { return optionalText(user.getFullName(), "Người dùng Telegram"); }
    private String optionalText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
