package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.MaintenanceService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.format.DateTimeFormatter;

@Component
public class MaintenanceCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceCommandHandler.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final MaintenanceService maintenanceService;
    private final ConversationStateService stateService;

    public MaintenanceCommandHandler(MaintenanceService maintenanceService, ConversationStateService stateService) {
        this.maintenanceService = maintenanceService;
        this.stateService = stateService;
    }

    @Override
    public String getCommand() {
        return "/maintenance";
    }

    @Override
    public String getDescription() {
        return "Quản lý và báo cáo sự cố bảo trì";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        String action = update.hasMessage() ? update.getMessage().getText() : update.getCallbackQuery().getData();
        Long actionChatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        var actionStep = stateService.getState(user.getTelegramId()).getStep();
        if (!com.boardinghousebot.bot.command.RoleActionPolicy.allows(user, getCommand(), action, actionStep, update.hasMessage())) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(actionChatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        Long chatId = extractChatId(update);
        String text = extractText(update);
        UserConversationState state = stateService.getState(user.getTelegramId());

        if (text == null) return;

        log.info("MAINTENANCE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), text);

        if ("/cancel".equalsIgnoreCase(text) || "CANCEL_MAINTENANCE".equals(text)) {
            boolean isUpdate = state.getStep().name().startsWith("UPDATE_MAINTENANCE");
            boolean isCreate = state.getStep().name().startsWith("CREATE_MAINTENANCE");
            stateService.clearState(user.getTelegramId());

            String cancelMsg = isCreate ? "❌ Đã hủy thao tác báo cáo sự cố." :
                    isUpdate ? "❌ Đã hủy thao tác xử lý sự cố." : "❌ Đã hủy thao tác.";

            if (user.getRole() == Role.MEMBER) {
                messageSender.sendMessageWithKeyboard(chatId, cancelMsg, TelegramKeyboardUtil.getMemberMenu());
            } else {
                messageSender.sendMessageWithKeyboard(chatId, cancelMsg, TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
            }
            return;
        }

        // Callbacks for status updates
        if (text.startsWith("MAINTENANCE_UPDATE_IN_PROGRESS_")) {
            Long requestId = Long.parseLong(text.replace("MAINTENANCE_UPDATE_IN_PROGRESS_", "").trim());
            messageSender.sendNotice(chatId, "Đã bỏ trạng thái Đang xử lý. Khi xử lý xong, hãy bấm Hoàn thành.");
            showMaintenanceDetail(chatId, user, requestId, messageSender);
            return;
        }

        if (text.startsWith("MAINTENANCE_UPDATE_RESOLVED_")) {
            Long requestId = Long.parseLong(text.replace("MAINTENANCE_UPDATE_RESOLVED_", "").trim());
            updateStatus(chatId, user, requestId, MaintenanceStatus.RESOLVED, messageSender);
            return;
        }

        // Active conversation steps
        if (state.getStep() != UserConversationState.Step.NONE) {
            handleConversationStep(chatId, user, text, state, messageSender);
            return;
        }

        // Details callback
        if (text.startsWith("MAINTENANCE_DETAIL_") || text.startsWith("/maintenance_detail_")) {
            String idStr = text.startsWith("MAINTENANCE_DETAIL_") ? text.replace("MAINTENANCE_DETAIL_", "") : text.replace("/maintenance_detail_", "");
            showMaintenanceDetail(chatId, user, Long.parseLong(idStr.trim()), messageSender);
            return;
        }

        switch (text) {
            case "LANDLORD_MAINTENANCE", "MEMBER_MAINTENANCE", "MAINTENANCE_MENU", "/maintenance" -> showMaintenanceMenu(chatId, user, messageSender);
            case "MAINTENANCE_ADD" -> startCreateMaintenanceFlow(chatId, user, state, messageSender);
            case "MAINTENANCE_LIST_ALL" -> listMaintenanceRequests(chatId, user, null, messageSender);
            case "MAINTENANCE_LIST_PENDING", "MAINTENANCE_LIST_IN_PROGRESS" -> listMaintenanceRequests(chatId, user, null, messageSender);
            case "MAINTENANCE_LIST_RESOLVED" -> listMaintenanceRequests(chatId, user, MaintenanceStatus.RESOLVED, messageSender);
            case "MAINTENANCE_LIST_MEMBER" -> messageSender.sendMessageWithKeyboard(chatId, "📌 MENU CHÍNH", TelegramKeyboardUtil.getMemberMenu());
            default -> showMaintenanceMenu(chatId, user, messageSender);
        }
    }

    private void showMaintenanceMenu(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() == Role.MEMBER) {
            startCreateMaintenanceFlow(chatId, user, stateService.getState(user.getTelegramId()), messageSender);
        } else {
            messageSender.sendMessageWithKeyboard(chatId, "🔧 BẢO TRÌ & SỰ CỐ\n\nChọn nhóm sự cố:",
                    TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
        }
    }

    private void startCreateMaintenanceFlow(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.MEMBER) {
            messageSender.sendNotice(chatId, "❌ Chỉ người thuê phòng mới có thể báo cáo sự cố.");
            return;
        }

        state.setStep(UserConversationState.Step.CREATE_MAINTENANCE_DESC);
        stateService.setState(user.getTelegramId(), state);

        messageSender.sendPrompt(chatId, "🔧 BÁO CÁO SỰ CỐ\n\nNhập chi tiết mô tả sự cố:");
    }

    private void updateStatus(Long chatId, User user, Long requestId, MaintenanceStatus newStatus, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendNotice(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        try {
            MaintenanceRequest req = maintenanceService.updateMaintenanceStatus(user.getTelegramId(), requestId, newStatus, "");
            stateService.clearState(user.getTelegramId());
            messageSender.sendResultAndMenu(chatId, "✅ Sự cố #" + req.getId() + ": " + req.getStatus().getDisplayName(),
                    TelegramKeyboardUtil.getMaintenanceDetailKeyboard(req.getId(), req.getStatus()));
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void handleConversationStep(Long chatId, User user, String input, UserConversationState state, BotMessageSender messageSender) {
        switch (state.getStep()) {
            case CREATE_MAINTENANCE_TITLE, CREATE_MAINTENANCE_DESC -> {
                String desc = input.trim();
                if (desc.isEmpty() || desc.length() > 500) {
                    messageSender.sendNotice(chatId, "❌ Mô tả không được để trống và không quá 500 ký tự. Vui lòng nhập lại:");
                    return;
                }
                String title = desc.length() <= 100 ? desc : desc.substring(0, 97) + "...";

                try {
                    MaintenanceRequest req = maintenanceService.createMaintenanceRequest(user.getTelegramId(), title, desc);
                    stateService.clearState(user.getTelegramId());

                    String successMsg = String.format("""
                            ✅ Đã gửi báo cáo sự cố thành công!

                            🆔 Mã sự cố: #%d
                            🏠 Phòng: %s
                            📝 Mô tả: %s
                            📊 Trạng thái: %s %s

                            Chủ nhà sẽ nhận được thông báo và xử lý sớm nhất.
                            """,
                            req.getId(),
                            req.getRoom().getRoomNumber(),
                            req.getDescription(),
                            req.getStatus().getIcon(),
                            req.getStatus().getDisplayName());

                    messageSender.sendResultAndMenu(chatId, successMsg, TelegramKeyboardUtil.getMemberMenu());
                } catch (BusinessException e) {
                    stateService.clearState(user.getTelegramId());
                    messageSender.sendNotice(chatId, "❌ " + e.getMessage());
                }
            }
            default -> {
                stateService.clearState(user.getTelegramId());
                showMaintenanceMenu(chatId, user, messageSender);
            }
        }
    }

    private void listMaintenanceRequests(Long chatId, User user, MaintenanceStatus filterStatus, BotMessageSender messageSender) {
        boolean completed = filterStatus == MaintenanceStatus.RESOLVED;
        var requests = maintenanceService.getAllMaintenanceRequests().stream()
                .filter(r -> (r.getStatus() == MaintenanceStatus.RESOLVED) == completed)
                .sorted(java.util.Comparator.comparingInt((MaintenanceRequest r) -> r.getStatus() == MaintenanceStatus.PENDING ? 0 : 1)
                        .thenComparing(MaintenanceRequest::getCreatedAt, java.util.Comparator.reverseOrder())
                        .thenComparing(MaintenanceRequest::getId, java.util.Comparator.reverseOrder()))
                .toList();
        var rows = new java.util.ArrayList<InlineKeyboardRow>();
        for (var request : requests) {
            String summary = request.getTitle().replaceAll("\\s+", " ").trim();
            if (summary.length() > 45) summary = summary.substring(0, 42) + "...";
            String icon = completed ? "✅" : "🆕";
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(
                    icon + " Phòng " + request.getRoom().getRoomNumber() + " · " + summary,
                    "MAINTENANCE_DETAIL_" + request.getId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Bảo trì", "LANDLORD_MAINTENANCE")));
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("⬅️ Menu chính", "MAIN_MENU")));
        String title = completed ? "✅ SỰ CỐ ĐÃ XỬ LÝ" : "🔧 SỰ CỐ CẦN XỬ LÝ";
        messageSender.sendMessageWithKeyboard(chatId, title + " · " + requests.size()
                + (requests.isEmpty() ? completed ? "\n\nChưa có sự cố đã xử lý." : "\n\nKhông có sự cố cần xử lý."
                : "\n\nChọn sự cố để xem chi tiết."), new InlineKeyboardMarkup(rows));
    }

    private void showMaintenanceDetail(Long chatId, User user, Long requestId, BotMessageSender messageSender) {
        MaintenanceRequest r = maintenanceService.getMaintenanceForUser(user, requestId);

        String detailText = String.format("""
                🔧 CHI TIẾT SỰ CỐ #%d

                🏠 Phòng: %s
                👤 Người báo: %s
                📝 Mô tả: %s
                📊 Trạng thái: %s %s
                🕒 Ngày báo: %s
                """,
                r.getId(),
                r.getRoom().getRoomNumber(),
                r.getMember().getFullName(),
                r.getDescription(),
                r.getStatus() == MaintenanceStatus.RESOLVED ? "✅" : "⏳",
                r.getStatus() == MaintenanceStatus.RESOLVED ? "Đã xử lý" : "Cần xử lý",
                r.getCreatedAt().format(DATE_TIME_FORMATTER)
        );
        if (r.getResolvedAt() != null)
            detailText += "✅ Hoàn thành lúc: " + r.getResolvedAt().format(DATE_TIME_FORMATTER) + "\n";

        if (user.getRole() == Role.LANDLORD) {
            messageSender.sendMessageWithKeyboard(chatId, detailText.trim(), TelegramKeyboardUtil.getMaintenanceDetailKeyboard(r.getId(), r.getStatus()));
        } else {
            messageSender.sendMessageWithKeyboard(chatId, detailText.trim(), TelegramKeyboardUtil.getMemberMenu());
        }
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
