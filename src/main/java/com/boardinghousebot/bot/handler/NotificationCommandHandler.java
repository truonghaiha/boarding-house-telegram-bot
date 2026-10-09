package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.NotificationService;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class NotificationCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(NotificationCommandHandler.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final NotificationService notificationService;

    public NotificationCommandHandler(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public String getCommand() {
        return "/notification";
    }

    @Override
    public String getDescription() {
        return "Xem và quản lý thông báo hệ thống";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        Long chatId = extractChatId(update);
        String text = extractText(update);

        if (text == null) return;

        log.info("NOTIFICATION ROUTING: telegramId={}, input={}", user.getTelegramId(), text);

        if (text.startsWith("NOTIFICATION_READ_")) {
            Long notifId = Long.parseLong(text.replace("NOTIFICATION_READ_", "").trim());
            notificationService.markAsRead(notifId, user.getTelegramId());
            listNotifications(chatId, user, false, messageSender);
            return;
        }

        switch (text) {
            case "LANDLORD_NOTIFICATIONS", "MEMBER_NOTIFICATIONS", "NOTIFICATION_MENU", "NOTIFICATION_LIST_ALL", "/notification" ->
                    listNotifications(chatId, user, false, messageSender);
            case "NOTIFICATION_LIST_UNREAD" ->
                    listNotifications(chatId, user, true, messageSender);
            case "NOTIFICATION_MARK_ALL_READ" -> {
                notificationService.markAllAsRead(user.getTelegramId());
                messageSender.sendMessageWithKeyboard(chatId, "✅ Đã đánh dấu tất cả thông báo là đã đọc.", TelegramKeyboardUtil.getNotificationMenu());
            }
            default -> listNotifications(chatId, user, false, messageSender);
        }
    }

    private void listNotifications(Long chatId, User user, boolean unreadOnly, BotMessageSender messageSender) {
        List<Notification> notifications = unreadOnly ?
                notificationService.getUnreadNotificationsForUser(user.getTelegramId()) :
                notificationService.getNotificationsForUser(user.getTelegramId());

        long unreadCount = notificationService.getUnreadCount(user.getTelegramId());

        String header = String.format("🔔 DANH SÁCH THÔNG BÁO %s\n📩 Chưa đọc: %d thông báo\n\n",
                unreadOnly ? "(CHƯA ĐỌC)" : "", unreadCount);

        if (notifications.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, header + "Không có thông báo nào.", TelegramKeyboardUtil.getNotificationMenu());
            return;
        }

        StringBuilder sb = new StringBuilder(header);
        for (Notification n : notifications) {
            String readStatus = n.isRead() ? "✓ Đã đọc" : "🔴 MỚI";
            String title = n.getTitle();
            String icon = n.getType().getIcon();
            if (icon != null && title != null && title.startsWith(icon)) {
                title = title.substring(icon.length()).stripLeading();
            }
            sb.append(icon).append(" ").append(title).append(" (").append(readStatus).append(")\n");
            sb.append(n.getMessage()).append("\n");
            sb.append("🕒 ").append(n.getCreatedAt().format(DATE_TIME_FORMATTER)).append("\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getNotificationMenu());
    }

    private Long extractChatId(Update update) {
        if (update.hasMessage()) return update.getMessage().getChatId();
        if (update.hasCallbackQuery() && update.getCallbackQuery().getMessage() != null) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }

    private String extractText(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            return update.getMessage().getText();
        }
        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getData();
        }
        return null;
    }
}
