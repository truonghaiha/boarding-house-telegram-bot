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

import java.time.format.DateTimeFormatter;
import java.util.List;

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
                messageSender.sendMessageWithKeyboard(chatId, cancelMsg, TelegramKeyboardUtil.getMemberMaintenanceMenu());
            } else {
                messageSender.sendMessageWithKeyboard(chatId, cancelMsg, TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
            }
            return;
        }

        // Callbacks for status updates
        if (text.startsWith("MAINTENANCE_UPDATE_IN_PROGRESS_")) {
            Long requestId = Long.parseLong(text.replace("MAINTENANCE_UPDATE_IN_PROGRESS_", "").trim());
            startUpdateStatusFlow(chatId, user, requestId, MaintenanceStatus.IN_PROGRESS, state, messageSender);
            return;
        }

        if (text.startsWith("MAINTENANCE_UPDATE_RESOLVED_")) {
            Long requestId = Long.parseLong(text.replace("MAINTENANCE_UPDATE_RESOLVED_", "").trim());
            startUpdateStatusFlow(chatId, user, requestId, MaintenanceStatus.RESOLVED, state, messageSender);
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
            case "MAINTENANCE_LIST_PENDING" -> listMaintenanceRequests(chatId, user, MaintenanceStatus.PENDING, messageSender);
            case "MAINTENANCE_LIST_IN_PROGRESS" -> listMaintenanceRequests(chatId, user, MaintenanceStatus.IN_PROGRESS, messageSender);
            case "MAINTENANCE_LIST_RESOLVED" -> listMaintenanceRequests(chatId, user, MaintenanceStatus.RESOLVED, messageSender);
            case "MAINTENANCE_LIST_MEMBER" -> listMemberMaintenanceRequests(chatId, user, messageSender);
            default -> showMaintenanceMenu(chatId, user, messageSender);
        }
    }

    private void showMaintenanceMenu(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() == Role.MEMBER) {
            messageSender.sendMessageWithKeyboard(chatId, "🔧 BÁO CÁO SỰ CỐ & BẢO TRÌ\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getMemberMaintenanceMenu());
        } else {
            messageSender.sendMessageWithKeyboard(chatId, "🔧 QUẢN LÝ BẢO TRÌ & SỰ CỐ\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
        }
    }

    private void startCreateMaintenanceFlow(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.MEMBER) {
            messageSender.sendMessage(chatId, "❌ Chỉ người thuê phòng mới có thể báo cáo sự cố.");
            return;
        }

        state.setStep(UserConversationState.Step.CREATE_MAINTENANCE_TITLE);
        stateService.setState(user.getTelegramId(), state);

        messageSender.sendMessage(chatId, "🔧 BÁO CÁO SỰ CỐ MỚI\n\nNhập tiêu đề sự cố (ví dụ: Hỏng vòi nước, Mất điện phòng):\n\nGửi /cancel để hủy.");
    }

    private void startUpdateStatusFlow(Long chatId, User user, Long requestId, MaintenanceStatus newStatus, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD && user.getRole() != Role.ADMIN) {
            messageSender.sendMessage(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        state.getData().put("requestId", requestId);
        state.getData().put("newStatus", newStatus.name());
        state.setStep(UserConversationState.Step.UPDATE_MAINTENANCE_NOTE);
        stateService.setState(user.getTelegramId(), state);

        String promptMsg = String.format("🔧 XỬ LÝ SỰ CỐ #%d -> %s %s\n\nNhập ghi chú xử lý (ví dụ: Đã hẹn thợ sửa lúc 14h hoặc gửi '-' nếu không có ghi chú):\n\nGửi /cancel để hủy.",
                requestId, newStatus.getIcon(), newStatus.getDisplayName());

        messageSender.sendMessage(chatId, promptMsg);
    }

    private void handleConversationStep(Long chatId, User user, String input, UserConversationState state, BotMessageSender messageSender) {
        switch (state.getStep()) {
            case CREATE_MAINTENANCE_TITLE -> {
                String title = input.trim();
                if (title.isEmpty() || title.length() > 100) {
                    messageSender.sendMessage(chatId, "❌ Tiêu đề không được để trống và không quá 100 ký tự. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("title", title);
                state.setStep(UserConversationState.Step.CREATE_MAINTENANCE_DESC);
                stateService.setState(user.getTelegramId(), state);

                messageSender.sendMessage(chatId, "📝 Nhập chi tiết mô tả sự cố (ví dụ: Vòi nước bồn rửa mặt bị gãy gây rò rỉ nước):\n\nGửi /cancel để hủy.");
            }
            case CREATE_MAINTENANCE_DESC -> {
                String desc = input.trim();
                if (desc.isEmpty() || desc.length() > 500) {
                    messageSender.sendMessage(chatId, "❌ Mô tả không được để trống và không quá 500 ký tự. Vui lòng nhập lại:");
                    return;
                }
                String title = (String) state.getData().get("title");

                try {
                    MaintenanceRequest req = maintenanceService.createMaintenanceRequest(user.getTelegramId(), title, desc);
                    stateService.clearState(user.getTelegramId());

                    String successMsg = String.format("""
                            ✅ Đã gửi báo cáo sự cố thành công!
                            
                            🆔 Mã sự cố: #%d
                            🏠 Phòng: %s
                            📌 Tiêu đề: %s
                            📝 Mô tả: %s
                            📊 Trạng thái: %s %s
                            
                            Chủ nhà sẽ nhận được thông báo và xử lý sớm nhất.
                            """,
                            req.getId(),
                            req.getRoom().getRoomNumber(),
                            req.getTitle(),
                            req.getDescription(),
                            req.getStatus().getIcon(),
                            req.getStatus().getDisplayName());

                    messageSender.sendMessageWithKeyboard(chatId, successMsg, TelegramKeyboardUtil.getMemberMaintenanceMenu());
                } catch (BusinessException e) {
                    stateService.clearState(user.getTelegramId());
                    messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getMemberMaintenanceMenu());
                }
            }
            case UPDATE_MAINTENANCE_NOTE -> {
                Long requestId = (Long) state.getData().get("requestId");
                MaintenanceStatus newStatus = MaintenanceStatus.valueOf((String) state.getData().get("newStatus"));
                String note = "-".equals(input.trim()) ? "" : input.trim();

                try {
                    MaintenanceRequest req = maintenanceService.updateMaintenanceStatus(user.getTelegramId(), requestId, newStatus, note);
                    stateService.clearState(user.getTelegramId());

                    String successMsg = String.format("""
                            ✅ Đã cập nhật trạng thái sự cố #%d!
                            
                            🏠 Phòng: %s
                            📌 Tiêu đề: %s
                            📊 Trạng thái mới: %s %s
                            📝 Ghi chú chủ nhà: %s
                            """,
                            req.getId(),
                            req.getRoom().getRoomNumber(),
                            req.getTitle(),
                            req.getStatus().getIcon(),
                            req.getStatus().getDisplayName(),
                            req.getLandlordNote() != null && !req.getLandlordNote().isBlank() ? req.getLandlordNote() : "(Không)");

                    messageSender.sendMessageWithKeyboard(chatId, successMsg, TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
                } catch (BusinessException e) {
                    stateService.clearState(user.getTelegramId());
                    messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
                }
            }
            default -> {
                stateService.clearState(user.getTelegramId());
                showMaintenanceMenu(chatId, user, messageSender);
            }
        }
    }

    private void listMaintenanceRequests(Long chatId, User user, MaintenanceStatus filterStatus, BotMessageSender messageSender) {
        List<MaintenanceRequest> requests = filterStatus == null ?
                maintenanceService.getAllMaintenanceRequests() :
                maintenanceService.getMaintenanceRequestsByStatus(filterStatus);

        String title = filterStatus == null ? "🔧 DANH SÁCH BẢO TRÌ & SỰ CỐ" : "🔧 SỰ CỐ: " + filterStatus.getDisplayName();

        if (requests.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, title + "\n\nKhông có yêu cầu sự cố nào.", TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
            return;
        }

        StringBuilder sb = new StringBuilder(title + "\n\n");
        for (MaintenanceRequest r : requests) {
            sb.append("🆔 #").append(r.getId()).append(" - ").append(r.getStatus().getIcon()).append(" ").append(r.getStatus().getDisplayName()).append("\n");
            sb.append("   🏠 Phòng: ").append(r.getRoom().getRoomNumber()).append(" (").append(r.getMember().getFullName()).append(")\n");
            sb.append("   📌 Tiêu đề: ").append(r.getTitle()).append("\n");
            sb.append("   🕒 Thời gian: ").append(r.getCreatedAt().format(DATE_TIME_FORMATTER)).append("\n");
            sb.append("   Chi tiết: `/maintenance_detail_").append(r.getId()).append("`\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getLandlordMaintenanceManagementMenu());
    }

    private void listMemberMaintenanceRequests(Long chatId, User user, BotMessageSender messageSender) {
        List<MaintenanceRequest> requests = maintenanceService.getMaintenanceRequestsByMember(user.getTelegramId());

        if (requests.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "🔧 LỊCH SỬ BÁO SỰ CỐ\n\nBạn chưa gửi báo cáo sự cố nào.", TelegramKeyboardUtil.getMemberMaintenanceMenu());
            return;
        }

        StringBuilder sb = new StringBuilder("🔧 LỊCH SỬ BÁO SỰ CỐ PHÒNG TÔI\n\n");
        for (MaintenanceRequest r : requests) {
            sb.append("🆔 #").append(r.getId()).append(" - ").append(r.getStatus().getIcon()).append(" ").append(r.getStatus().getDisplayName()).append("\n");
            sb.append("   📌 Tiêu đề: ").append(r.getTitle()).append("\n");
            sb.append("   📝 Mô tả: ").append(r.getDescription()).append("\n");
            sb.append("   🕒 Ngày báo: ").append(r.getCreatedAt().format(DATE_TIME_FORMATTER)).append("\n");
            if (r.getLandlordNote() != null && !r.getLandlordNote().isBlank()) {
                sb.append("   💬 Phản hồi chủ nhà: ").append(r.getLandlordNote()).append("\n");
            }
            sb.append("\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getMemberMaintenanceMenu());
    }

    private void showMaintenanceDetail(Long chatId, User user, Long requestId, BotMessageSender messageSender) {
        MaintenanceRequest r = maintenanceService.getMaintenanceRequestById(requestId);

        String detailText = String.format("""
                🔧 CHI TIẾT SỰ CỐ #%d
                
                🏠 Phòng: %s
                👤 Người báo: %s (SĐT: %s)
                📌 Tiêu đề: %s
                📝 Mô tả: %s
                📊 Trạng thái: %s %s
                🕒 Ngày báo: %s
                %s
                💬 Ghi chú xử lý: %s
                """,
                r.getId(),
                r.getRoom().getRoomNumber(),
                r.getMember().getFullName(),
                r.getMember().getUsername() != null ? "@" + r.getMember().getUsername() : "ID: " + r.getMember().getTelegramId(),
                r.getTitle(),
                r.getDescription(),
                r.getStatus().getIcon(), r.getStatus().getDisplayName(),
                r.getCreatedAt().format(DATE_TIME_FORMATTER),
                r.getResolvedAt() != null ? "✅ Hoàn thành lúc: " + r.getResolvedAt().format(DATE_TIME_FORMATTER) + "\n" : "",
                r.getLandlordNote() != null && !r.getLandlordNote().isBlank() ? r.getLandlordNote() : "(Chưa có)"
        );

        if (user.getRole() == Role.LANDLORD || user.getRole() == Role.ADMIN) {
            messageSender.sendMessageWithKeyboard(chatId, detailText.trim(), TelegramKeyboardUtil.getMaintenanceDetailKeyboard(r.getId(), r.getStatus()));
        } else {
            messageSender.sendMessage(chatId, detailText.trim());
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
