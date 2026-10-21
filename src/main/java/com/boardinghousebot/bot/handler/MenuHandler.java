package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.audit.AuditLog;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.AuditLogService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.service.StatisticsService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class MenuHandler implements CommandHandler {

    private final UserService userService;
    private final AuditLogService auditLogService;
    private final StatisticsService statisticsService;
    private final ConversationStateService stateService;
    private final StartHandler startHandler;
    private final com.boardinghousebot.service.AuthorizationService authorizationService;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public MenuHandler(UserService userService, AuditLogService auditLogService, StatisticsService statisticsService, ConversationStateService stateService, StartHandler startHandler,
                       com.boardinghousebot.service.AuthorizationService authorizationService) {
        this.userService = userService;
        this.auditLogService = auditLogService;
        this.statisticsService = statisticsService;
        this.stateService = stateService;
        this.startHandler = startHandler;
        this.authorizationService = authorizationService;
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
        String action = update.hasMessage() ? update.getMessage().getText() : update.getCallbackQuery().getData();
        Long actionChatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        var actionStep = stateService.getState(user.getTelegramId()).getStep();
        if (action != null && (action.startsWith("ADMIN_") || action.startsWith("/promote")
                || action.startsWith("/revoke") || action.startsWith("/search_users")
                || actionStep == UserConversationState.Step.ADMIN_SEARCH_USERS
                || actionStep.name().startsWith("ADMIN_ROLE_CHANGE"))) {
            if (!authorizationService.hasRole(user, Role.ADMIN)) {
                stateService.clearState(user.getTelegramId());
                messageSender.sendNotice(actionChatId, "❌ Bạn không có quyền thực hiện chức năng này.");
                return;
            }
        }
        if (!com.boardinghousebot.bot.command.RoleActionPolicy.allows(user, getCommand(), action, actionStep, update.hasMessage())) {
            messageSender.sendNotice(actionChatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        if (update.hasMessage()) {
            Long chatId = update.getMessage().getChatId();
            String text = update.getMessage().getText();

            if (actionStep.name().startsWith("ADMIN_ROLE_CHANGE")
                    && text != null && "/cancel".equalsIgnoreCase(text.trim())) {
                stateService.clearState(user.getTelegramId());
                messageSender.sendMessageWithKeyboard(chatId, "👥 QUẢN LÝ NGƯỜI DÙNG",
                        TelegramKeyboardUtil.getAdminUserManagementMenu());
                return;
            }

            if (actionStep == UserConversationState.Step.ADMIN_ROLE_CHANGE_INPUT && text != null && !text.startsWith("/")) {
                try {
                    Long targetId = Long.parseLong(text.trim());
                    User target = userService.findByTelegramIdWithRoom(targetId)
                            .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng. Vui lòng nhập lại ID."));
                    var state = stateService.getState(user.getTelegramId());
                    state.setTargetUserTelegramId(targetId);
                    state.setStep(UserConversationState.Step.ADMIN_ROLE_CHANGE_CONFIRM);
                    stateService.setState(user.getTelegramId(), state);
                    boolean promote = Boolean.TRUE.equals(state.getData().get("promote"));
                    messageSender.sendMessageWithKeyboard(chatId, "✅ XÁC NHẬN " + (promote ? "CẤP" : "THU HỒI")
                            + " QUYỀN LANDLORD\n\n👤 " + target.getFullName() + "\nTelegram ID: " + targetId,
                            TelegramKeyboardUtil.getConfirmKeyboard("ADMIN_ROLE_CONFIRM_" + targetId, "/cancel"));
                } catch (NumberFormatException e) {
                    messageSender.sendNotice(chatId, "❌ Telegram ID phải là một số nguyên hợp lệ. Vui lòng nhập lại.");
                } catch (BusinessException e) { messageSender.sendNotice(chatId, "❌ " + e.getMessage()); }
                return;
            }
            if (actionStep == UserConversationState.Step.ADMIN_ROLE_CHANGE_CONFIRM && text != null && !text.startsWith("/")) {
                messageSender.sendNotice(chatId, "Vui lòng bấm Xác nhận hoặc Hủy.");
                return;
            }

            if (actionStep == UserConversationState.Step.ADMIN_SEARCH_USERS || (text != null && text.startsWith("/search_users"))) {
                if (user.getRole() != Role.ADMIN) {
                    stateService.clearState(user.getTelegramId());
                    messageSender.sendNotice(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
                    return;
                }
                String query = text.startsWith("/search_users") ? text.substring("/search_users".length()).trim() : text.trim();
                if ("/cancel".equals(query)) {
                    stateService.clearState(user.getTelegramId());
                    messageSender.sendMessageWithKeyboard(chatId, "Đã hủy tìm kiếm.", TelegramKeyboardUtil.getAdminUserManagementMenu());
                } else if (query.isBlank()) {
                    messageSender.sendPrompt(chatId, "Nhập tên, username hoặc Telegram ID cần tìm:");
                } else {
                    stateService.clearState(user.getTelegramId());
                    showUsers(chatId, userService.searchUsersAsAdmin(user, query), "🔎 KẾT QUẢ TÌM KIẾM", messageSender);
                }
                return;
            }

            if (text != null && text.startsWith("/promote")) {
                handlePromoteCommand(chatId, text, user, messageSender);
                return;
            }

            if (text != null && text.startsWith("/revoke")) {
                handleRevokeCommand(chatId, text, user, messageSender);
                return;
            }

            if (user.getRole() == Role.MEMBER) startHandler.showMemberMenu(update, user, messageSender, false);
            else messageSender.sendMessageWithKeyboard(chatId, "📌 MENU CHỨC NĂNG (" + user.getRole() + ")", TelegramKeyboardUtil.getRoleMenu(user.getRole()));
            return;
        }

        if (update.hasCallbackQuery()) {
            Long chatId = update.getCallbackQuery().getMessage().getChatId();
            String data = update.getCallbackQuery().getData();
            if (data.startsWith("ADMIN_ROLE_CONFIRM_")) {
                var state = stateService.getState(user.getTelegramId());
                if (state.getStep() != UserConversationState.Step.ADMIN_ROLE_CHANGE_CONFIRM
                        || !("ADMIN_ROLE_CONFIRM_" + state.getTargetUserTelegramId()).equals(data)) {
                    messageSender.sendNotice(chatId, "❌ Thao tác đã hết hiệu lực. Vui lòng bắt đầu lại.");
                    return;
                }
                try {
                    Long targetId = state.getTargetUserTelegramId();
                    boolean promote = Boolean.TRUE.equals(state.getData().get("promote"));
                    User saved = promote ? userService.promoteToLandlord(user.getTelegramId(), targetId)
                            : userService.revokeLandlord(user.getTelegramId(), targetId);
                    stateService.clearState(user.getTelegramId());
                    messageSender.sendResultAndMenu(chatId, "✅ Đã " + (promote ? "cấp" : "thu hồi")
                            + " quyền LANDLORD cho " + saved.getFullName() + " (ID: " + targetId + ").", TelegramKeyboardUtil.getAdminUserManagementMenu());
                } catch (BusinessException e) { messageSender.sendNotice(chatId, "❌ " + e.getMessage()); }
                return;
            }

            switch (data) {
                case "ACCOUNT_INFO" -> {
                    if (user.getRole() == Role.MEMBER) startHandler.showMemberAccount(update, user, messageSender);
                    else showAccountInfo(chatId, user, messageSender);
                }
                case "MEMBER_CHECK_STATUS" -> {
                    if (user.getRole() == Role.MEMBER) startHandler.checkMemberStatus(update, user, messageSender);
                    else messageSender.sendNotice(chatId, "❌ Chỉ MEMBER mới có trạng thái thuê phòng.");
                }
                case "MEMBER_CONTACT_INFO" -> {
                    if (user.getRole() == Role.MEMBER) startHandler.showMemberContact(update, user, messageSender);
                    else messageSender.sendNotice(chatId, "❌ Chỉ MEMBER mới có thể xem thông tin liên hệ này.");
                }
                case "ADMIN_USERS" -> messageSender.sendMessageWithKeyboard(chatId, "👥 QUẢN LÝ NGƯỜI DÙNG", TelegramKeyboardUtil.getAdminUserManagementMenu());
                case "ADMIN_LIST_USERS" -> listAllUsers(chatId, user, messageSender);
                case "ADMIN_SEARCH_USERS" -> {
                    stateService.setState(user.getTelegramId(), new UserConversationState(UserConversationState.Step.ADMIN_SEARCH_USERS));
                    messageSender.sendPrompt(chatId, "🔎 Nhập tên, username hoặc Telegram ID cần tìm.");
                }
                case "ADMIN_STATS" -> showSystemStats(chatId, user, messageSender);
                case "ADMIN_PROMOTE_INPUT" -> showRoleChangePrompt(chatId, user, true, messageSender);
                case "ADMIN_REVOKE_INPUT" -> showRoleChangePrompt(chatId, user, false, messageSender);
                case "ADMIN_AUDIT" -> showAuditLogs(chatId, user, messageSender);
                case "MAIN_MENU" -> {
                    if (user.getRole() == Role.MEMBER) startHandler.showMemberMenu(update, user, messageSender, false);
                    else messageSender.sendMessageWithKeyboard(chatId, "📌 MENU CHÍNH", TelegramKeyboardUtil.getRoleMenu(user.getRole()));
                }
                default -> messageSender.sendNotice(chatId, "🚧 Chức năng này sẽ được triển khai ở Phase tiếp theo.");
            }
        }
    }

    private void showUsers(Long chatId, List<User> users, String title, BotMessageSender sender) {
        StringBuilder text = new StringBuilder(title).append("\n\n");
        if (users.isEmpty()) text.append("Không tìm thấy người dùng.");
        for (User account : users) {
            text.append(account.getFullName()).append("\nID: ").append(account.getTelegramId())
                    .append(" | Vai trò: ").append(account.getRole()).append("\n\n");
        }
        sender.sendMessageWithKeyboard(chatId, text.toString().trim(), TelegramKeyboardUtil.getAdminUserManagementMenu());
    }

    private void showSystemStats(Long chatId, User user, BotMessageSender sender) {
        var stats = statisticsService.systemStats(user);
        sender.sendMessageWithKeyboard(chatId, String.format("📊 THỐNG KÊ HỆ THỐNG\n\nTổng tài khoản: %d\nADMIN: %d\nLANDLORD: %d\nMEMBER: %d\nHoạt động được ghi nhật ký: %d",
                stats.users(), stats.admins(), stats.landlords(), stats.members(), stats.auditEntries()), TelegramKeyboardUtil.getAdminMenu());
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
        messageSender.sendMessageWithKeyboard(chatId, infoText, TelegramKeyboardUtil.getRoleMenu(user.getRole()));
    }

    private void listAllUsers(Long chatId, User currentUser, BotMessageSender messageSender) {
        if (currentUser.getRole() != Role.ADMIN) {
            messageSender.sendNotice(chatId, "Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<User> users = userService.getAllUsers().stream()
                .sorted(java.util.Comparator.comparing(User::getRole))
                .toList();
        if (users.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "👥 DANH SÁCH NGƯỜI DÙNG\n\nChưa có người dùng nào trong hệ thống.", TelegramKeyboardUtil.getAdminUserManagementMenu());
            return;
        }

        StringBuilder sb = new StringBuilder("👥 DANH SÁCH NGƯỜI DÙNG\n\n");
        int index = 1;
        for (User u : users) {
            sb.append(index++).append(". ").append(u.getFullName() != null ? u.getFullName() : "User").append("\n");
            sb.append("   ID (Telegram): ").append(u.getTelegramId()).append("\n");
            sb.append("   Role: ").append(u.getRole()).append("\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getAdminUserManagementMenu());
    }

    private void showRoleChangePrompt(Long chatId, User user, boolean promote, BotMessageSender messageSender) {
        var state = new UserConversationState(UserConversationState.Step.ADMIN_ROLE_CHANGE_INPUT);
        state.getData().put("promote", promote);
        stateService.setState(user.getTelegramId(), state);
        String title = promote ? "⬆️ CẤP QUYỀN LANDLORD" : "⬇️ THU HỒI LANDLORD";
        messageSender.sendMessageWithKeyboard(chatId, title + "\n\nNhập Telegram ID của người dùng cần "
                + (promote ? "cấp" : "thu hồi") + " quyền LANDLORD:\nVí dụ: 123456789", TelegramKeyboardUtil.getAdminRoleChangePromptKeyboard());
    }

    private void handlePromoteCommand(Long chatId, String text, User adminUser, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                showRoleChangePrompt(chatId, adminUser, true, messageSender);
                return;
            }
            Long targetId = Long.parseLong(parts[1]);
            User updated = userService.promoteToLandlord(adminUser.getTelegramId(), targetId);
            stateService.clearState(adminUser.getTelegramId());
            messageSender.sendResultAndMenu(chatId, "✅ Đã cấp quyền LANDLORD thành công cho người dùng: " + updated.getFullName() + " (ID: " + targetId + ")", TelegramKeyboardUtil.getAdminUserManagementMenu());
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void handleRevokeCommand(Long chatId, String text, User adminUser, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                showRoleChangePrompt(chatId, adminUser, false, messageSender);
                return;
            }
            Long targetId = Long.parseLong(parts[1]);
            User updated = userService.revokeLandlord(adminUser.getTelegramId(), targetId);
            stateService.clearState(adminUser.getTelegramId());
            messageSender.sendResultAndMenu(chatId, "✅ Đã thu hồi quyền LANDLORD thành công đối với người dùng: " + updated.getFullName() + " (ID: " + targetId + ")", TelegramKeyboardUtil.getAdminUserManagementMenu());
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void showAuditLogs(Long chatId, User adminUser, BotMessageSender messageSender) {
        if (adminUser.getRole() != Role.ADMIN) {
            messageSender.sendNotice(chatId, "Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<AuditLog> logs = auditLogService.getAllLogs();
        if (logs.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "📋 NHẬT KÝ HỆ THỐNG\n\nChưa có nhật ký hoạt động nào.", TelegramKeyboardUtil.getAdminMenu());
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

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getAdminMenu());
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
