package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.state.UserConversationState.Step;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Optional;

@Component
public class RoomCommandHandler implements CommandHandler {

    private final RoomService roomService;
    private final ConversationStateService stateService;
    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,### VNĐ");

    public RoomCommandHandler(RoomService roomService, ConversationStateService stateService) {
        this.roomService = roomService;
        this.stateService = stateService;
    }

    @Override
    public String getCommand() {
        return "/room";
    }

    @Override
    public String getDescription() {
        return "Quản lý phòng và người thuê";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        Long chatId = update.hasMessage() ? update.getMessage().getChatId()
                : update.getCallbackQuery().getMessage().getChatId();
        Long telegramId = user.getTelegramId();

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();

            if ("/cancel".equalsIgnoreCase(text) || "❌ Hủy".equalsIgnoreCase(text)) {
                stateService.clearState(telegramId);
                messageSender.sendMessageWithKeyboard(chatId, "❌ Đã hủy thao tác.",
                        TelegramKeyboardUtil.getRoleMenu(user.getRole()));
                return;
            }

            UserConversationState state = stateService.getState(telegramId);
            if (state.getStep() != Step.NONE) {
                handleConversationStep(chatId, text, user, state, messageSender);
                return;
            }
        }

        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();

            if ("LANDLORD_ROOMS".equals(data)) {
                messageSender.sendMessageWithKeyboard(chatId, "🏠 QUẢN LÝ PHÒNG\n\nVui lòng chọn thao tác bên dưới:",
                        TelegramKeyboardUtil.getLandlordRoomManagementMenu());
                return;
            }
            if ("ROOM_ADD".equals(data)) {
                startRoomCreation(chatId, user, messageSender);
                return;
            }
            if ("ROOM_LIST_ALL".equals(data)) {
                listRooms(chatId, user, messageSender, "ALL");
                return;
            }
            if ("ROOM_LIST_AVAILABLE".equals(data)) {
                listRooms(chatId, user, messageSender, "AVAILABLE");
                return;
            }
            if ("ROOM_LIST_OCCUPIED".equals(data)) {
                listRooms(chatId, user, messageSender, "OCCUPIED");
                return;
            }
            if (data.startsWith("ROOM_DETAIL_")) {
                Long roomId = Long.parseLong(data.substring("ROOM_DETAIL_".length()));
                showRoomDetail(chatId, user, roomId, messageSender);
                return;
            }
            if (data.startsWith("ROOM_DELETE_")) {
                Long roomId = Long.parseLong(data.substring("ROOM_DELETE_".length()));
                deleteRoom(chatId, user, roomId, messageSender);
                return;
            }
            if ("CONFIRM_CREATE_ROOM".equals(data)) {
                confirmRoomCreation(chatId, user, messageSender);
                return;
            }
            if ("CANCEL_CREATE_ROOM".equals(data)) {
                stateService.clearState(telegramId);
                messageSender.sendMessageWithKeyboard(chatId, "❌ Đã hủy tạo phòng.",
                        TelegramKeyboardUtil.getLandlordRoomManagementMenu());
                return;
            }
            if ("MEMBER_ROOM".equals(data)) {
                showMemberRoom(chatId, user, messageSender);
                return;
            }
        }

        messageSender.sendMessageWithKeyboard(chatId, "🏠 QUẢN LÝ PHÒNG\n\nVui lòng chọn thao tác bên dưới:",
                TelegramKeyboardUtil.getLandlordRoomManagementMenu());
    }

    private void startRoomCreation(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD && user.getRole() != Role.ADMIN) {
            messageSender.sendMessage(chatId, "Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        UserConversationState state = new UserConversationState(Step.CREATE_ROOM_NUMBER);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendMessage(chatId,
                "➕ THÊM PHÒNG MỚI\n\nBước 1/4: Nhập số phòng (Ví dụ: `101`, `102`):\n\n*(Nhập /cancel để hủy bất cứ lúc nào)*");
    }

    private void handleConversationStep(Long chatId, String input, User user, UserConversationState state,
            BotMessageSender messageSender) {
        Long telegramId = user.getTelegramId();

        try {
            switch (state.getStep()) {
                case CREATE_ROOM_NUMBER -> {
                    if (roomService.getRoomByRoomNumber(input).isPresent()) {
                        messageSender.sendMessage(chatId,
                                "❌ Số phòng `" + input + "` đã tồn tại. Vui lòng nhập số phòng khác:");
                        return;
                    }
                    state.setRoomNumber(input);
                    state.setStep(Step.CREATE_ROOM_RENT);
                    messageSender.sendMessage(chatId, "Bước 2/4: Nhập giá thuê tháng (VNĐ) (Ví dụ: `3500000`):");
                }
                case CREATE_ROOM_RENT -> {
                    BigDecimal rent = new BigDecimal(input);
                    if (rent.compareTo(BigDecimal.ZERO) <= 0) {
                        messageSender.sendMessage(chatId, "❌ Giá thuê phải lớn hơn 0. Vui lòng nhập lại:");
                        return;
                    }
                    state.setMonthlyRent(rent);
                    state.setStep(Step.CREATE_ROOM_MAX);
                    messageSender.sendMessage(chatId, "Bước 3/4: Nhập số người ở tối đa (Ví dụ: `4`):");
                }
                case CREATE_ROOM_MAX -> {
                    int max = Integer.parseInt(input);
                    if (max <= 0) {
                        messageSender.sendMessage(chatId, "❌ Số người tối đa phải lớn hơn 0. Vui lòng nhập lại:");
                        return;
                    }
                    state.setMaxOccupants(max);
                    state.setStep(Step.CREATE_ROOM_DESC);
                    messageSender.sendMessage(chatId, "Bước 4/4: Nhập mô tả phòng (hoặc gõ `/skip` để bỏ qua):");
                }
                case CREATE_ROOM_DESC -> {
                    String desc = "/skip".equalsIgnoreCase(input) ? "Chưa có mô tả" : input;
                    state.setDescription(desc);
                    state.setStep(Step.CREATE_ROOM_CONFIRM);

                    String summary = String.format("""
                            🏠 XÁC NHẬN TẠO PHÒNG MỚI

                            • Số phòng: %s
                            • Giá thuê: %s
                            • Số người tối đa: %d người
                            • Mô tả: %s
                            """,
                            state.getRoomNumber(),
                            CURRENCY_FORMAT.format(state.getMonthlyRent()),
                            state.getMaxOccupants(),
                            state.getDescription());
                    messageSender.sendMessageWithKeyboard(chatId, summary,
                            TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_CREATE_ROOM", "CANCEL_CREATE_ROOM"));
                }
                default -> {
                    stateService.clearState(telegramId);
                }
            }
        } catch (NumberFormatException e) {
            messageSender.sendMessage(chatId, "❌ Dữ liệu nhập vào phải là một số hợp lệ. Vui lòng nhập lại:");
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void confirmRoomCreation(Long chatId, User user, BotMessageSender messageSender) {
        UserConversationState state = stateService.getState(user.getTelegramId());
        if (state.getStep() != Step.CREATE_ROOM_CONFIRM) {
            messageSender.sendMessage(chatId, "❌ Không tìm thấy thông tin tạo phòng.");
            stateService.clearState(user.getTelegramId());
            return;
        }

        try {
            Room created = roomService.createRoom(
                    user.getTelegramId(),
                    state.getRoomNumber(),
                    state.getMonthlyRent(),
                    state.getMaxOccupants(),
                    state.getDescription());
            stateService.clearState(user.getTelegramId());

            messageSender.sendMessageWithKeyboard(
                    chatId,
                    "✅ Đã tạo phòng thành công: " + created.getRoomNumber(),
                    TelegramKeyboardUtil.getLandlordRoomManagementMenu());
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void listRooms(Long chatId, User user, BotMessageSender messageSender, String filter) {
        List<Room> rooms = switch (filter) {
            case "AVAILABLE" -> roomService.getAvailableRooms();
            case "OCCUPIED" -> roomService.getOccupiedRooms();
            default -> roomService.getAllRooms();
        };

        if (rooms.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "🏠 DANH SÁCH PHÒNG\n\nChưa có phòng nào tương ứng.",
                    TelegramKeyboardUtil.getLandlordRoomManagementMenu());
            return;
        }

        StringBuilder sb = new StringBuilder("🏠 DANH SÁCH PHÒNG (" + rooms.size() + " phòng)\n\n");
        for (Room r : rooms) {
            int occupants = roomService.getCurrentOccupantCount(r.getId());
            String badge = r.isFull(occupants) ? "🔴 Hết chỗ" : (occupants > 0 ? "🟢 Còn chỗ" : "🟢 Đang trống");
            sb.append("• *Phòng ").append(r.getRoomNumber()).append("*\n");
            sb.append("  💰 ").append(CURRENCY_FORMAT.format(r.getMonthlyRent())).append("\n");
            sb.append("  👥 ").append(occupants).append("/").append(r.getMaxOccupants()).append(" người\n");
            sb.append("  📌 ").append(badge).append("\n");
            sb.append("  Chi tiết: `/room_detail_").append(r.getId()).append("`\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(),
                TelegramKeyboardUtil.getLandlordRoomManagementMenu());
    }

    private void showRoomDetail(Long chatId, User user, Long roomId, BotMessageSender messageSender) {
        Room room = roomService.getRoomById(roomId);
        List<User> tenants = roomService.getRoomTenants(roomId);
        int occupants = roomService.getCurrentOccupantCount(roomId);

        StringBuilder tenantsStr = new StringBuilder();
        if (tenants == null || tenants.isEmpty()) {
            tenantsStr.append("- Chưa có người ở");
        } else {
            for (User t : tenants) {
                tenantsStr.append("- ").append(t.getFullName() != null ? t.getFullName() : t.getUsername())
                        .append(" (ID: ").append(t.getTelegramId()).append(")\n");
            }
        }

        String statusBadge = room.getStatus() == RoomStatus.OCCUPIED ? "Đang ở" : "Đang trống";
        String detailText = String.format("""
                🏠 PHÒNG %s

                💰 Giá thuê: %s
                👥 Số người: %d/%d người
                📌 Trạng thái: %s

                👤 Người thuê:
                %s
                📝 Mô tả: %s
                """,
                room.getRoomNumber(),
                CURRENCY_FORMAT.format(room.getMonthlyRent()),
                occupants,
                room.getMaxOccupants(),
                statusBadge,
                tenantsStr.toString().trim(),
                room.getDescription() != null ? room.getDescription() : "Không có");

        messageSender.sendMessageWithKeyboard(chatId, detailText,
                TelegramKeyboardUtil.getRoomDetailKeyboard(room.getId()));
    }

    private void deleteRoom(Long chatId, User user, Long roomId, BotMessageSender messageSender) {
        try {
            roomService.deleteRoom(user.getTelegramId(), roomId);
            messageSender.sendMessageWithKeyboard(chatId, "✅ Đã xóa phòng thành công.",
                    TelegramKeyboardUtil.getLandlordRoomManagementMenu());
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void showMemberRoom(Long chatId, User user, BotMessageSender messageSender) {
        Optional<Room> roomOpt = roomService.getMemberRoom(user.getTelegramId());

        if (roomOpt.isEmpty()) {
            messageSender.sendMessage(chatId,
                    "🏠 PHÒNG CỦA TÔI\n\nBạn hiện chưa được xếp vào phòng nào trong nhà trọ.");
            return;
        }

        Room room = roomOpt.get();
        List<User> tenants = roomService.getRoomTenants(room.getId());
        int occupants = roomService.getCurrentOccupantCount(room.getId());

        List<User> coTenants = (tenants != null) ? tenants.stream()
                .filter(t -> t.getTelegramId() == null || !t.getTelegramId().equals(user.getTelegramId()))
                .toList() : List.of();

        StringBuilder cotenantsSb = new StringBuilder();
        if (!coTenants.isEmpty()) {
            for (User tenant : coTenants) {
                cotenantsSb.append("- ").append(tenant.getFullName() != null ? tenant.getFullName() : "Thành viên")
                        .append("\n");
            }
        } else {
            cotenantsSb.append("- Không có thành viên khác\n");
        }

        String memberText = String.format("""
                🏠 PHÒNG CỦA TÔI (PHÒNG %s)

                💰 Giá thuê: %s
                👥 Số người ở: %d/%d người
                📌 Trạng thái: Đang ở

                👤 Người ở cùng:
                %s
                📝 Mô tả phòng: %s
                """,
                room.getRoomNumber(),
                CURRENCY_FORMAT.format(room.getMonthlyRent()),
                occupants,
                room.getMaxOccupants(),
                cotenantsSb.toString().trim(),
                room.getDescription() != null ? room.getDescription() : "Không có thành viên khác");

        messageSender.sendMessage(chatId, memberText);
    }
}
