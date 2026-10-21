package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.NotificationService;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

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
        String action = update.hasMessage() ? update.getMessage().getText() : update.getCallbackQuery().getData();
        Long actionChatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        var actionStep = com.boardinghousebot.state.UserConversationState.Step.NONE;
        if (!com.boardinghousebot.bot.command.RoleActionPolicy.allows(user, getCommand(), action, actionStep, update.hasMessage())) {
            messageSender.sendNotice(actionChatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        Long chatId = extractChatId(update);
        String text = extractText(update);

        if (text == null) return;

        log.info("NOTIFICATION ROUTING: telegramId={}, input={}", user.getTelegramId(), text);

        try {
            if (text.startsWith("NOTIFICATION_DETAIL_")) {
                showDetail(chatId, user, Long.parseLong(text.substring("NOTIFICATION_DETAIL_".length())), messageSender);
            } else if (text.startsWith("NOTIFICATION_READ_")) {
                Long id = Long.parseLong(text.substring("NOTIFICATION_READ_".length()));
                notificationService.markAsRead(id, user.getTelegramId());
                showDetail(chatId, user, id, messageSender);
            } else switch (text) {
                case "NOTIFICATION_LIST_UNREAD" -> listNotifications(chatId, user, false, messageSender);
                case "NOTIFICATION_LIST_READ" -> listNotifications(chatId, user, true, messageSender);
                case "NOTIFICATION_MARK_ALL_READ" -> {
                    notificationService.markAllAsRead(user.getTelegramId());
                    messageSender.sendNotice(chatId, "✅ Đã đánh dấu tất cả thông báo là đã đọc.");
                    listNotifications(chatId, user, false, messageSender);
                }
                default -> messageSender.sendMessageWithKeyboard(chatId, "🔔 THÔNG BÁO\n\nChọn nhóm thông báo:", TelegramKeyboardUtil.getNotificationMenu());
            }
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void listNotifications(Long chatId, User user, boolean read, BotMessageSender sender) {
        List<Notification> notifications = read ? notificationService.getReadNotificationsForUser(user.getTelegramId())
                : notificationService.getUnreadNotificationsForUser(user.getTelegramId());
        var rows = new java.util.ArrayList<InlineKeyboardRow>();
        for (Notification n : notifications) {
            String label = notificationTitle(n).replaceAll("\\s+", " ");
            if (label.length() > 65) label = label.substring(0, 62) + "...";
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "NOTIFICATION_DETAIL_" + n.getId())));
        }
        if (!read && !notifications.isEmpty()) rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(
                "✅ Đánh dấu tất cả đã đọc", "NOTIFICATION_MARK_ALL_READ")));
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Thông báo", "NOTIFICATION_MENU")));
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Menu chính", "MAIN_MENU")));
        String title = read ? "✅ THÔNG BÁO ĐÃ ĐỌC" : "📩 THÔNG BÁO CHƯA ĐỌC";
        sender.sendMessageWithKeyboard(chatId, title + " · " + notifications.size()
                + (notifications.isEmpty() ? "\n\nKhông có thông báo nào." : "\n\nChọn thông báo để xem chi tiết."), new InlineKeyboardMarkup(rows));
    }

    private String notificationTitle(Notification notification) {
        String title = notification.getTitle();
        String icon = notification.getType().getIcon();
        if (title.startsWith(icon)) title = title.substring(icon.length()).stripLeading();
        return icon + " " + title;
    }

    private void showDetail(Long chatId, User user, Long id, BotMessageSender sender) {
        Notification notification = notificationService.getNotificationForUser(id, user.getTelegramId());
        var rows = new java.util.ArrayList<InlineKeyboardRow>();
        if (!notification.isRead()) rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("✅ Đã đọc", "NOTIFICATION_READ_" + id)));
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Danh sách thông báo",
                notification.isRead() ? "NOTIFICATION_LIST_READ" : "NOTIFICATION_LIST_UNREAD")));
        sender.sendMessageWithKeyboard(chatId, notificationTitle(notification) + "\n\n" + notification.getMessage()
                + "\n\n🕒 " + notification.getCreatedAt().format(DATE_TIME_FORMATTER), new InlineKeyboardMarkup(rows));
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
