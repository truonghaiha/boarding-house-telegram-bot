package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.audit.AuditLog;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.AuditLogService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class MenuHandler implements CommandHandler {

    private final UserService userService;
    private final AuditLogService auditLogService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public MenuHandler(UserService userService, AuditLogService auditLogService) {
        this.userService = userService;
        this.auditLogService = auditLogService;
    }

    @Override
    public String getCommand() {
        return "/menu";
    }

    @Override
    public String getDescription() {
        return "Mở menu chức năng";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        if (update.hasMessage()) {
            Long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();

            if (text != null && text.startsWith("/promote")) {
                handlePromoteCommand(chatId, text, user, messageSender);
                return;
            }

            if (text != null && text.startsWith("/revoke")) {
                handleRevokeCommand(chatId, text, user, messageSender);
                return;
            }

            messageSender.sendMessageWithKeyboard(chatId, "📌 MENU CHỨC NĂNG (" + user.getRole() + ")", TelegramKeyboardUtil.getRoleMenu(user.getRole()));
            return;
        }

        if (update.hasCallbackQuery()) {
            Long chatId = update.getCallbackQuery().getMessage().getChatId();
            String data = update.getCallbackQuery().getData();

            switch (data) {
                case "ACCOUNT_INFO" -> showAccountInfo(chatId, user, messageSender);
                case "ADMIN_USERS" -> messageSender.sendMessageWithKeyboard(chatId, "👥 QUẢN LÝ NGƯỜI DÙNG", TelegramKeyboardUtil.getAdminUserManagementMenu());
                case "ADMIN_LIST_USERS" -> listAllUsers(chatId, user, messageSender);
                case "ADMIN_PROMOTE_INPUT" -> messageSender.sendMessage(chatId, "⬆️ CẤP QUYỀN LANDLORD\n\nVui lòng nhập lệnh dạng:\n`/promote <Telegram_ID>`\n\nVí dụ: `/promote 123456789`");
                case "ADMIN_REVOKE_INPUT" -> messageSender.sendMessage(chatId, "⬇️ THU HỒI LANDLORD\n\nVui lòng nhập lệnh dạng:\n`/revoke <Telegram_ID>`\n\nVí dụ: `/revoke 123456789`");
                case "ADMIN_AUDIT" -> showAuditLogs(chatId, user, messageSender);
                case "MAIN_MENU" -> messageSender.sendMessageWithKeyboard(chatId, "📌 MENU CHÍNH", TelegramKeyboardUtil.getRoleMenu(user.getRole()));
                default -> messageSender.sendMessage(chatId, "🚧 Chức năng này sẽ được triển khai ở Phase tiếp theo.");
            }
        }
    }

    private void showAccountInfo(Long chatId, User user, BotMessageSender messageSender) {
        String createdStr = user.getCreatedAt() != null ? user.getCreatedAt().format(DATE_FORMATTER) : "N/A";
        String infoText = String.format("""
                👤 THÔNG TIN TÀI KHOẢN
                
                ID: %d
                Username: %s
                Họ tên: %s
                Vai trò: %s
                Ngày tham gia: %s
                """,
                user.getTelegramId(),
                user.getUsername() != null ? user.getUsername() : "Chưa đặt",
                user.getFullName() != null ? user.getFullName() : "N/A",
                user.getRole(),
                createdStr
        );
        messageSender.sendMessage(chatId, infoText);
    }

    private void listAllUsers(Long chatId, User currentUser, BotMessageSender messageSender) {
        if (currentUser.getRole() != Role.ADMIN) {
            messageSender.sendMessage(chatId, "Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<User> users = userService.getAllUsers();
        if (users.isEmpty()) {
            messageSender.sendMessage(chatId, "👥 DANH SÁCH NGƯỜI DÙNG\n\nChưa có người dùng nào trong hệ thống.");
            return;
        }

        StringBuilder sb = new StringBuilder("👥 DANH SÁCH NGƯỜI DÙNG\n\n");
        int index = 1;
        for (User u : users) {
            sb.append(index++).append(". ").append(u.getFullName() != null ? u.getFullName() : "User").append("\n");
            sb.append("   ID (Telegram): ").append(u.getTelegramId()).append("\n");
            sb.append("   Role: ").append(u.getRole()).append("\n\n");
        }

        messageSender.sendMessage(chatId, sb.toString().trim());
    }

    private void handlePromoteCommand(Long chatId, String text, User adminUser, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                messageSender.sendMessage(chatId, "Cú pháp không đúng. Vui lòng nhập: `/promote <Telegram_ID>`");
                return;
            }
            Long targetId = Long.parseLong(parts[1]);
            User updated = userService.promoteToLandlord(adminUser.getTelegramId(), targetId);
            messageSender.sendMessage(chatId, "✅ Đã cấp quyền LANDLORD thành công cho người dùng: " + updated.getFullName() + " (ID: " + targetId + ")");
        } catch (NumberFormatException e) {
            messageSender.sendMessage(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void handleRevokeCommand(Long chatId, String text, User adminUser, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                messageSender.sendMessage(chatId, "Cú pháp không đúng. Vui lòng nhập: `/revoke <Telegram_ID>`");
                return;
            }
            Long targetId = Long.parseLong(parts[1]);
            User updated = userService.revokeLandlord(adminUser.getTelegramId(), targetId);
            messageSender.sendMessage(chatId, "✅ Đã thu hồi quyền LANDLORD thành công đối với người dùng: " + updated.getFullName() + " (ID: " + targetId + ")");
        } catch (NumberFormatException e) {
            messageSender.sendMessage(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void showAuditLogs(Long chatId, User adminUser, BotMessageSender messageSender) {
        if (adminUser.getRole() != Role.ADMIN) {
            messageSender.sendMessage(chatId, "Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<AuditLog> logs = auditLogService.getAllLogs();
        if (logs.isEmpty()) {
            messageSender.sendMessage(chatId, "📋 NHẬT KÝ HỆ THỐNG\n\nChưa có nhật ký hoạt động nào.");
            return;
        }

        StringBuilder sb = new StringBuilder("📋 NHẬT KÝ HỆ THỐNG (Top 10 mới nhất)\n\n");
        int count = 0;
        for (AuditLog logItem : logs) {
            if (count++ >= 10) break;
            String timeStr = logItem.getCreatedAt() != null ? logItem.getCreatedAt().format(DATE_FORMATTER) : "";
            sb.append("• [").append(timeStr).append("] User ").append(logItem.getTelegramId()).append("\n");
            String formattedAction = formatActionName(logItem.getAction());
            sb.append("  Action: `").append(formattedAction).append("`\n");
            sb.append("  ").append(logItem.getDescription()).append("\n\n");
        }

        messageSender.sendMessage(chatId, sb.toString().trim());
    }

    private String formatActionName(String rawAction) {
        if (rawAction == null) return "UNKNOWN";
        return switch (rawAction.toUpperCase()) {
            case "ADMINPROMOTELANDLORD" -> "ADMIN_PROMOTE_LANDLORD";
            case "ADMINREVOKELANDLORD" -> "ADMIN_REVOKE_LANDLORD";
            case "USERREGISTER" -> "USER_REGISTER";
            default -> rawAction;
        };
    }
}
