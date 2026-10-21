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
import com.boardinghousebot.util.RoomFloorUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class RoomCommandHandler implements CommandHandler {

    private final RoomService roomService;
    private final ConversationStateService stateService;
    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,### VNĐ");
    private static final int PAGE_SIZE = 10;

    public RoomCommandHandler(RoomService roomService, ConversationStateService stateService) {
        this.roomService = roomService;
        this.stateService = stateService;
    }

    @Override
    public String getCommand() {
        return "/room";
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

        Long chatId = update.hasMessage() ? update.getMessage().getChatId()
                : update.getCallbackQuery().getMessage().getChatId();
        Long telegramId = user.getTelegramId();
        if (user.getRole() == Role.MEMBER) {
            if (action.startsWith("MEMBER_AVAILABLE_ROOMS")) {
                int page = 0;
                try {
                    if (action.startsWith("MEMBER_AVAILABLE_ROOMS_"))
                        page = Integer.parseInt(action.substring("MEMBER_AVAILABLE_ROOMS_".length()));
                } catch (NumberFormatException ignored) {}
                showAvailableRooms(chatId, page, messageSender);
            } else showMemberRoom(chatId, user, messageSender);
            return;
        }

        String removalAction = update.hasMessage() ? update.getMessage().getText().trim() : action;
        UserConversationState removalState = stateService.getState(telegramId);
        if (removalAction.startsWith("ROOM_EDIT_")
                || removalState.getStep().name().startsWith("EDIT_ROOM") && update.hasMessage()) {
            handleRoomEdit(chatId, user, removalAction, removalState, messageSender);
            return;
        }
        boolean removing = removalState.getStep().name().startsWith("REMOVE_ROOM_TENANT");
        if ("CANCEL_ROOM_REMOVE_TENANT".equals(removalAction)
                || removing && ("/cancel".equalsIgnoreCase(removalAction) || "❌ Hủy".equals(removalAction))) {
            Long roomId = removalState.getTargetRoomId();
            stateService.clearState(telegramId);
            if (roomId == null) {
                messageSender.sendMessageWithKeyboard(chatId, "❌ Đã hủy thao tác đưa người thuê ra khỏi phòng.",
                        TelegramKeyboardUtil.getLandlordRoomManagementMenu());
            } else {
                messageSender.sendNotice(chatId, "❌ Đã hủy thao tác đưa người thuê ra khỏi phòng.");
                showRemovalRoom(chatId, user, roomId, messageSender);
            }
            return;
        }
        if (update.hasCallbackQuery() && removalAction.startsWith("ROOM_REMOVE_")) {
            try {
                handleRoomTenantRemoval(chatId, user, removalAction, removalState, messageSender);
            } catch (BusinessException | NumberFormatException e) {
                Long roomId = removalState.getTargetRoomId();
                stateService.clearState(telegramId);
                messageSender.sendNotice(chatId, "❌ " + (e instanceof BusinessException ? e.getMessage() : "Lựa chọn không hợp lệ."));
                if (roomId != null) showRemovalRoom(chatId, user, roomId, messageSender);
            }
            return;
        }
        if (removing && update.hasMessage()) {
            messageSender.sendNotice(chatId, "Vui lòng chọn người thuê hoặc xác nhận bằng nút bên dưới.");
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText().trim();

            if ("/cancel".equalsIgnoreCase(text) || "❌ Hủy".equalsIgnoreCase(text)) {
                stateService.clearState(telegramId);
                messageSender.sendMessageWithKeyboard(chatId, "❌ Đã hủy thao tác.",
                        TelegramKeyboardUtil.getLandlordRoomManagementMenu());
                return;
            }

            UserConversationState state = stateService.getState(telegramId);
            if (state.getStep() != Step.NONE) {
                handleConversationStep(chatId, text, user, state, messageSender);
                return;
            }

            if (text.startsWith("/room_detail_")) {
                String command = text.split("\\s+", 2)[0].split("@", 2)[0];
                showRequestedRoomDetail(chatId, user, command.substring("/room_detail_".length()), null, messageSender);
                return;
            }
        }

        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();
            Integer messageId = update.getCallbackQuery().getMessage().getMessageId();

            if ("LANDLORD_ROOMS".equals(data)) {
                showRoomManagement(chatId, messageSender);
                return;
            }
            if ("ROOM_ADD".equals(data)) {
                startRoomCreation(chatId, user, messageSender);
                return;
            }
            if ("ROOM_LIST_ALL".equals(data)) {
                showFloors(chatId, messageId, 0, messageSender);
                return;
            }
            if (data.startsWith("ROOM_FLOORS_") || data.startsWith("ROOM_FLOOR_")) {
                try {
                    if (data.startsWith("ROOM_FLOORS_")) {
                        showFloors(chatId, messageId, Integer.parseInt(data.substring("ROOM_FLOORS_".length())), messageSender);
                    } else {
                        String[] selection = data.substring("ROOM_FLOOR_".length()).split("_", -1);
                        if (selection.length != 2 && selection.length != 3) throw new NumberFormatException();
                        String filter = selection.length == 3 ? selection[2] : "ALL";
                        validateRoomFilter(filter);
                        showFloorRooms(chatId, messageId, selection[0], Integer.parseInt(selection[1]), filter, messageSender);
                    }
                } catch (NumberFormatException e) {
                    messageSender.sendMessageWithKeyboard(chatId, "❌ Lựa chọn tầng hoặc bộ lọc không hợp lệ.",
                            TelegramKeyboardUtil.getFloorListKeyboard(Map.of(), 0, 1));
                }
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
                showRequestedRoomDetail(chatId, user, data.substring("ROOM_DETAIL_".length()), messageId, messageSender);
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

        showRoomManagement(chatId, messageSender);
    }

    private void handleRoomEdit(Long chatId, User user, String action, UserConversationState state, BotMessageSender sender) {
        if ("/cancel".equalsIgnoreCase(action) || "❌ Hủy".equals(action)) {
            Long roomId = state.getTargetRoomId();
            stateService.clearState(user.getTelegramId());
            sender.sendNotice(chatId, "❌ Đã hủy sửa thông tin phòng.");
            if (roomId != null) showRemovalRoom(chatId, user, roomId, sender);
            else showRoomManagement(chatId, sender);
            return;
        }
        try {
            if (action.startsWith("ROOM_EDIT_AREA_") || action.startsWith("ROOM_EDIT_DEPOSIT_")) {
                boolean area = action.startsWith("ROOM_EDIT_AREA_");
                Long id = Long.parseLong(action.substring((area ? "ROOM_EDIT_AREA_" : "ROOM_EDIT_DEPOSIT_").length()));
                Room room = roomService.getRoomById(id);
                var next = new UserConversationState(area ? Step.EDIT_ROOM_AREA : Step.EDIT_ROOM_DEPOSIT);
                next.setTargetRoomId(id);
                next.getData().put("editField", area ? "area" : "deposit");
                stateService.setState(user.getTelegramId(), next);
                sender.sendPrompt(chatId, (area ? "📐 Nhập diện tích mới (m², lớn hơn 0)" : "💵 Nhập tiền cọc yêu cầu mới (VNĐ, không âm)")
                        + " cho phòng " + room.getRoomNumber() + ":");
                return;
            }
            if (action.startsWith("ROOM_EDIT_RENT_") || action.startsWith("ROOM_EDIT_DESC_")) {
                boolean rent = action.startsWith("ROOM_EDIT_RENT_");
                Long roomId = Long.parseLong(action.substring((rent ? "ROOM_EDIT_RENT_" : "ROOM_EDIT_DESC_").length()));
                Room room = roomService.getRoomById(roomId);
                UserConversationState next = new UserConversationState(rent ? Step.EDIT_ROOM_RENT : Step.EDIT_ROOM_DESC);
                next.setTargetRoomId(roomId);
                next.getData().put("editField", rent ? "rent" : "description");
                stateService.setState(user.getTelegramId(), next);
                sender.sendPrompt(chatId, rent ? "💰 SỬA GIÁ PHÒNG " + room.getRoomNumber()
                        + "\n\nGiá hiện tại: " + CURRENCY_FORMAT.format(room.getMonthlyRent())
                        + "\nNhập giá phòng mới (VNĐ, ví dụ: 4000000):"
                        : "✏️ SỬA MÔ TẢ PHÒNG " + room.getRoomNumber() + "\n\nMô tả hiện tại: "
                        + roomDescription(room) + "\n\nNhập mô tả mới:");
                return;
            }
            if (action.startsWith("ROOM_EDIT_CONFIRM_")) {
                Long roomId = Long.parseLong(action.substring("ROOM_EDIT_CONFIRM_".length()));
                if (state.getStep() != Step.EDIT_ROOM_CONFIRM || !roomId.equals(state.getTargetRoomId())) {
                    sender.sendNotice(chatId, "❌ Thao tác đã hết hiệu lực. Vui lòng bắt đầu sửa lại.");
                    return;
                }
                if ("area".equals(state.getData().get("editField")) || "deposit".equals(state.getData().get("editField"))) {
                    boolean area = "area".equals(state.getData().get("editField"));
                    roomService.updateRoomDetails(user.getTelegramId(), roomId,
                            area ? (BigDecimal) state.getData().get("detailValue") : null,
                            area ? null : (BigDecimal) state.getData().get("detailValue"));
                } else if ("rent".equals(state.getData().get("editField")))
                    roomService.updateRoomRent(user.getTelegramId(), roomId, state.getMonthlyRent());
                else roomService.updateRoomDescription(user.getTelegramId(), roomId, state.getDescription());
                stateService.clearState(user.getTelegramId());
                sender.sendNotice(chatId, "✅ Đã cập nhật thông tin phòng.");
                showRemovalRoom(chatId, user, roomId, sender);
                return;
            }
            if (state.getStep() == Step.EDIT_ROOM_AREA || state.getStep() == Step.EDIT_ROOM_DEPOSIT) {
                boolean area = state.getStep() == Step.EDIT_ROOM_AREA;
                BigDecimal value = new BigDecimal(action.trim());
                if (value.signum() < 0 || area && value.signum() == 0)
                    throw new BusinessException(area ? "Diện tích phải lớn hơn 0." : "Tiền cọc không được âm.");
                state.getData().put("detailValue", value);
                state.setStep(Step.EDIT_ROOM_CONFIRM);
                stateService.setState(user.getTelegramId(), state);
                sender.sendMessageWithKeyboard(chatId, "✅ XÁC NHẬN SỬA PHÒNG\n\n"
                        + (area ? "Diện tích mới: " + value.toPlainString() + " m²" : "Tiền cọc mới: " + CURRENCY_FORMAT.format(value))
                        + "\nHợp đồng đã lập giữ nguyên tiền cọc đã thỏa thuận.",
                        TelegramKeyboardUtil.getConfirmKeyboard("ROOM_EDIT_CONFIRM_" + state.getTargetRoomId(), "/cancel"));
                return;
            }
            if (state.getStep() != Step.EDIT_ROOM_RENT && state.getStep() != Step.EDIT_ROOM_DESC) {
                sender.sendNotice(chatId, "Vui lòng bấm Xác nhận để lưu hoặc Hủy.");
                return;
            }
            boolean rent = state.getStep() == Step.EDIT_ROOM_RENT;
            Room room = roomService.getRoomById(state.getTargetRoomId());
            if (rent) {
                BigDecimal value = new BigDecimal(action.trim());
                if (value.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("Giá phòng phải lớn hơn 0. Vui lòng nhập lại.");
                state.setMonthlyRent(value);
            } else {
                if (action.isBlank()) throw new BusinessException("Mô tả không được để trống. Vui lòng nhập lại.");
                state.setDescription(action.trim());
            }
            state.setStep(Step.EDIT_ROOM_CONFIRM);
            stateService.setState(user.getTelegramId(), state);
            sender.sendMessageWithKeyboard(chatId, "✅ XÁC NHẬN SỬA PHÒNG " + room.getRoomNumber() + "\n\n"
                    + (rent ? "💰 Giá mới: " + CURRENCY_FORMAT.format(state.getMonthlyRent())
                            : "📝 Mô tả mới: " + state.getDescription()),
                    TelegramKeyboardUtil.getConfirmKeyboard("ROOM_EDIT_CONFIRM_" + room.getId(), "/cancel"));
        } catch (NumberFormatException e) {
            sender.sendNotice(chatId, "❌ Vui lòng nhập số hợp lệ.");
        } catch (BusinessException e) {
            sender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void showRoomManagement(Long chatId, BotMessageSender sender) {
        List<Room> rooms = roomService.getAllRooms();
        Map<Long, Integer> occupants = roomOccupants(rooms);
        long available = rooms.stream().filter(r -> hasCapacity(r, occupants.get(r.getId()))).count();
        long full = rooms.stream().filter(r -> isFull(r, occupants.get(r.getId()))).count();
        long floors = RoomFloorUtil.groupRooms(rooms).keySet().stream()
                .filter(f -> !RoomFloorUtil.UNKNOWN_FLOOR.equals(f)).count();
        String text = "🏠 QUẢN LÝ PHÒNG\n\n"
                + "🏠 Tổng số phòng: " + rooms.size() + "\n"
                + "🏢 Tổng số tầng: " + floors + "\n"
                + "🟢 Phòng còn chỗ: " + available + "\n"
                + "🔴 Phòng đã đầy: " + full;
        if (rooms.isEmpty()) text += "\n\nChưa có phòng nào. Chọn Thêm phòng để bắt đầu.";
        sender.sendMessageWithKeyboard(chatId, text, TelegramKeyboardUtil.getLandlordRoomManagementMenu());
    }

    private Map<Long, Integer> roomOccupants(List<Room> rooms) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Room room : rooms) counts.put(room.getId(), roomService.getCurrentOccupantCount(room.getId()));
        return counts;
    }

    private boolean hasCapacity(Room room, int occupants) {
        return room.getMaxOccupants() != null && room.getMaxOccupants() > 0 && occupants >= 0
                && occupants < room.getMaxOccupants();
    }

    private boolean isFull(Room room, int occupants) {
        return room.getMaxOccupants() != null && room.getMaxOccupants() > 0 && occupants >= room.getMaxOccupants();
    }

    private void validateRoomFilter(String filter) {
        if (!List.of("ALL", "AVAILABLE", "FULL").contains(filter)) throw new NumberFormatException();
    }

    private void startRoomCreation(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendNotice(chatId, "Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        UserConversationState state = new UserConversationState(Step.CREATE_ROOM_NUMBER);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendPrompt(chatId,
                "➕ THÊM PHÒNG MỚI\n\nBước 1/6: Nhập số phòng (Ví dụ: `101`, `102`):");
    }

    private void handleConversationStep(Long chatId, String input, User user, UserConversationState state,
            BotMessageSender messageSender) {
        Long telegramId = user.getTelegramId();

        try {
            switch (state.getStep()) {
                case CREATE_ROOM_NUMBER -> {
                    if (roomService.getRoomByRoomNumber(input).isPresent()) {
                        messageSender.sendNotice(chatId,
                                "❌ Số phòng `" + input + "` đã tồn tại. Vui lòng nhập số phòng khác:");
                        return;
                    }
                    state.setRoomNumber(input);
                    state.setStep(Step.CREATE_ROOM_RENT);
                    messageSender.sendPrompt(chatId, "Bước 2/6: Nhập giá thuê tháng (VNĐ) (Ví dụ: `3500000`):");
                }
                case CREATE_ROOM_RENT -> {
                    BigDecimal rent = new BigDecimal(input);
                    if (rent.compareTo(BigDecimal.ZERO) <= 0) {
                        messageSender.sendNotice(chatId, "❌ Giá thuê phải lớn hơn 0. Vui lòng nhập lại:");
                        return;
                    }
                    state.setMonthlyRent(rent);
                    state.setStep(Step.CREATE_ROOM_MAX);
                    messageSender.sendPrompt(chatId, "Bước 3/6: Nhập số người ở tối đa (Ví dụ: `4`):");
                }
                case CREATE_ROOM_MAX -> {
                    int max = Integer.parseInt(input);
                    if (max <= 0) {
                        messageSender.sendNotice(chatId, "❌ Số người tối đa phải lớn hơn 0. Vui lòng nhập lại:");
                        return;
                    }
                    state.setMaxOccupants(max);
                    state.setStep(Step.CREATE_ROOM_AREA);
                    messageSender.sendPrompt(chatId, "Bước 4/6: Nhập diện tích phòng (m², ví dụ 25.5):");
                }
                case CREATE_ROOM_AREA -> {
                    BigDecimal area = new BigDecimal(input);
                    if (area.signum() <= 0) throw new BusinessException("Diện tích phải lớn hơn 0.");
                    state.getData().put("area", area);
                    state.setStep(Step.CREATE_ROOM_DEPOSIT);
                    messageSender.sendPrompt(chatId, "Bước 5/6: Nhập tiền cọc yêu cầu của phòng (VNĐ, nhập 0 nếu không yêu cầu cọc):");
                }
                case CREATE_ROOM_DEPOSIT -> {
                    BigDecimal deposit = new BigDecimal(input);
                    if (deposit.signum() < 0) throw new BusinessException("Tiền cọc không được âm.");
                    state.getData().put("deposit", deposit);
                    state.setStep(Step.CREATE_ROOM_DESC);
                    messageSender.sendOptionalPrompt(chatId, "Bước 6/6: Nhập mô tả phòng (có thể bỏ qua):", Step.CREATE_ROOM_DESC);
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
                    summary += "\n• Diện tích: " + state.getData().get("area") + " m²"
                            + "\n• Tiền cọc: " + CURRENCY_FORMAT.format(state.getData().get("deposit"));
                    messageSender.sendMessageWithKeyboard(chatId, summary,
                            TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_CREATE_ROOM", "CANCEL_CREATE_ROOM"));
                }
                default -> {
                    stateService.clearState(telegramId);
                }
            }
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "❌ Dữ liệu nhập vào phải là một số hợp lệ. Vui lòng nhập lại:");
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void confirmRoomCreation(Long chatId, User user, BotMessageSender messageSender) {
        UserConversationState state = stateService.getState(user.getTelegramId());
        if (state.getStep() != Step.CREATE_ROOM_CONFIRM) {
            messageSender.sendNotice(chatId, "❌ Không tìm thấy thông tin tạo phòng.");
            stateService.clearState(user.getTelegramId());
            return;
        }

        try {
            Room created = roomService.createRoom(
                    user.getTelegramId(),
                    state.getRoomNumber(),
                    state.getMonthlyRent(),
                    state.getMaxOccupants(),
                    state.getDescription(),
                    (BigDecimal) state.getData().get("area"),
                    (BigDecimal) state.getData().get("deposit"));
            stateService.clearState(user.getTelegramId());

            messageSender.sendResultAndMenu(
                    chatId,
                    "✅ Đã tạo phòng thành công: " + created.getRoomNumber(),
                    TelegramKeyboardUtil.getLandlordRoomManagementMenu());
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
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
            sb.append("  📝 Mô tả: ").append(roomDescription(r)).append("\n");
            sb.append("\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(),
                TelegramKeyboardUtil.getRoomListKeyboard(rooms));
    }

    private void showFloors(Long chatId, Integer messageId, int requestedPage, BotMessageSender sender) {
        Map<String, List<Room>> floors = RoomFloorUtil.groupRooms(roomService.getAllRooms());
        List<String> keys = new ArrayList<>(floors.keySet());
        int totalPages = pageCount(keys.size());
        int page = clampPage(requestedPage, totalPages);
        Map<String, Integer> visibleFloors = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder("🏢 DANH SÁCH TẦNG\n\n");
        if (keys.isEmpty()) text.append("Chưa có phòng nào.\n");
        for (String floor : pageItems(keys, page)) {
            int count = floors.get(floor).size();
            Map<Long, Integer> occupants = roomOccupants(floors.get(floor));
            long available = floors.get(floor).stream().filter(r -> hasCapacity(r, occupants.get(r.getId()))).count();
            visibleFloors.put(floor, count);
            text.append("• ").append(RoomFloorUtil.label(floor)).append(": ").append(count)
                    .append(" phòng · 🟢 ").append(available).append(" còn chỗ\n");
        }
        if (!keys.isEmpty()) text.append("\nChọn tầng để xem danh sách phòng.");
        appendPageNumber(text, page, totalPages);
        sender.sendMessageWithKeyboard(chatId, text.toString().trim(),
                TelegramKeyboardUtil.getFloorListKeyboard(visibleFloors, page, totalPages));
    }

    private void showFloorRooms(Long chatId, Integer messageId, String floor, int requestedPage, String filter, BotMessageSender sender) {
        Map<String, List<Room>> floors = RoomFloorUtil.groupRooms(roomService.getAllRooms());
        // A floor may have disappeared since the keyboard was sent (e.g. its last room was deleted).
        if (!floors.containsKey(floor)) {
            showFloors(chatId, messageId, 0, sender);
            return;
        }
        List<Room> floorRooms = floors.get(floor);
        Map<Long, Integer> occupantsByRoom = roomOccupants(floorRooms);
        List<Room> rooms = floorRooms.stream().filter(r -> switch (filter) {
            case "AVAILABLE" -> hasCapacity(r, occupantsByRoom.get(r.getId()));
            case "FULL" -> isFull(r, occupantsByRoom.get(r.getId()));
            default -> true;
        }).toList();
        int totalPages = pageCount(rooms.size());
        int page = clampPage(requestedPage, totalPages);
        List<Room> visibleRooms = pageItems(rooms, page);
        StringBuilder text = new StringBuilder("🏠 DANH SÁCH PHÒNG — " + RoomFloorUtil.label(floor)
                + " (" + floorRooms.size() + " phòng)\n\n");
        if (rooms.isEmpty()) text.append("Không có phòng phù hợp với bộ lọc đã chọn.\n\n");
        for (Room room : visibleRooms) {
            int occupants = occupantsByRoom.get(room.getId());
            String badge = room.getMaxOccupants() == null || room.getMaxOccupants() <= 0 || occupants < 0
                    ? "⚠️ Sức chứa không hợp lệ" : isFull(room, occupants) ? "🔴 Đã đầy"
                    : occupants == 0 ? "⚪ Trống" : "🟢 Còn chỗ";
            text.append("• Phòng ").append(room.getRoomNumber()).append(" · ").append(badge).append("\n")
                    .append("  💰 ").append(room.getMonthlyRent() == null ? "Chưa có giá" : CURRENCY_FORMAT.format(room.getMonthlyRent()))
                    .append(" · 👥 ").append(occupants).append("/")
                    .append(room.getMaxOccupants() == null ? "?" : room.getMaxOccupants()).append(" người\n\n");
        }
        // Remove the list's trailing blank line so the hint is separated by exactly one blank line.
        text.setLength(text.length() - 2);
        appendPageNumber(text, page, totalPages);
        if (!rooms.isEmpty()) text.append("\n\n👇 Chọn phòng để xem chi tiết.");
        int floorListPage = new ArrayList<>(floors.keySet()).indexOf(floor) / PAGE_SIZE;
        sender.sendMessageWithKeyboard(chatId, text.toString(),
                TelegramKeyboardUtil.getFloorRoomsKeyboard(visibleRooms, floor, page, totalPages, floorListPage, filter));
    }

    private int pageCount(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private int clampPage(int requestedPage, int totalPages) {
        return Math.max(0, Math.min(requestedPage, totalPages - 1));
    }

    private <T> List<T> pageItems(List<T> items, int page) {
        int start = page * PAGE_SIZE;
        return items.subList(start, Math.min(start + PAGE_SIZE, items.size()));
    }

    private void appendPageNumber(StringBuilder text, int page, int totalPages) {
        if (totalPages > 1) text.append("\nTrang ").append(page + 1).append("/").append(totalPages);
    }

    private void showRequestedRoomDetail(Long chatId, User user, String selection, Integer messageId, BotMessageSender messageSender) {
        try {
            String[] parts = selection.split("_F_", -1);
            String floor = null;
            int page = 0;
            String filter = "ALL";
            if (parts.length > 2) throw new NumberFormatException();
            if (parts.length == 2) {
                String[] context = parts[1].split("_", -1);
                if (context.length != 2 && context.length != 3) throw new NumberFormatException();
                floor = context[0];
                page = Math.max(0, Integer.parseInt(context[1]));
                if (context.length == 3) filter = context[2];
                validateRoomFilter(filter);
            }
            showRoomDetail(chatId, user, Long.parseLong(parts[0]), messageId, floor, page, filter, messageSender);
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "❌ Mã phòng không hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private String roomDescription(Room room) {
        return room.getDescription() == null || room.getDescription().isBlank()
                ? "Chưa có mô tả" : room.getDescription();
    }

    private void showRoomDetail(Long chatId, User user, Long roomId, Integer messageId, String sourceFloor,
                                int sourcePage, BotMessageSender messageSender) {
        showRoomDetail(chatId, user, roomId, messageId, sourceFloor, sourcePage, "ALL", messageSender);
    }

    private void showRoomDetail(Long chatId, User user, Long roomId, Integer messageId, String sourceFloor,
                                int sourcePage, String sourceFilter, BotMessageSender messageSender) {
        Room room = roomService.getRoomById(roomId);
        List<User> tenants = roomService.getRoomTenants(roomId);
        int occupants = roomService.getCurrentOccupantCount(roomId);

        StringBuilder tenantsStr = new StringBuilder();
        if (tenants == null || tenants.isEmpty()) {
            tenantsStr.append("- Chưa có người ở");
        } else {
            for (User t : tenants) {
                tenantsStr.append("- ").append(t.getFullName() != null ? t.getFullName() : t.getUsername())
                        .append(" (ID: ").append(t.getTelegramId()).append(")\n")
                        .append("- Số điện thoại: ").append(t.getPhoneNumberDisplay()).append("\n");
            }
        }

        String statusBadge = room.getStatus() == RoomStatus.OCCUPIED ? "Đang ở" : "Đang trống";
        String detailText = String.format("""
                🏠 CHI TIẾT PHÒNG %s

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
                roomDescription(room));

        detailText += "\n" + roomPublicDetails(room);
        String floor = RoomFloorUtil.floorOf(room);
        int page = floor.equals(sourceFloor) ? sourcePage : 0;
        String filterSuffix = floor.equals(sourceFloor) && !"ALL".equals(sourceFilter) ? "_" + sourceFilter : "";
        var keyboard = TelegramKeyboardUtil.getRoomDetailKeyboard(room.getId(), "ROOM_FLOOR_" + floor + "_" + page + filterSuffix);
        if (messageId == null) messageSender.sendMessageWithKeyboard(chatId, detailText, keyboard);
        else messageSender.sendOrEditMessageWithKeyboard(chatId, messageId, detailText, keyboard);
    }

    private void handleRoomTenantRemoval(Long chatId, User landlord, String action,
                                         UserConversationState state, BotMessageSender sender) {
        if (action.startsWith("ROOM_REMOVE_TENANTS_")) {
            Long roomId = Long.parseLong(action.substring("ROOM_REMOVE_TENANTS_".length()));
            Room room = roomService.getRoomById(roomId);
            List<User> tenants = roomService.getRoomTenants(roomId);
            if (tenants.isEmpty()) {
                stateService.clearState(landlord.getTelegramId());
                sender.sendNotice(chatId, "Phòng hiện không có người thuê để đưa ra khỏi phòng.");
                showRemovalRoom(chatId, landlord, roomId, sender);
                return;
            }
            UserConversationState next = new UserConversationState(Step.REMOVE_ROOM_TENANT_SELECT);
            next.setTargetRoomId(roomId);
            stateService.setState(landlord.getTelegramId(), next);
            var rows = new ArrayList<InlineKeyboardRow>();
            for (User tenant : tenants) {
                rows.add(new InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("👤 " + tenantName(tenant) + " (ID: " + tenant.getTelegramId() + ")",
                                "ROOM_REMOVE_SELECT_" + roomId + "_" + tenant.getTelegramId())));
            }
            rows.add(new InlineKeyboardRow(
                    TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_ROOM_REMOVE_TENANT")));
            sender.sendMessageWithKeyboard(chatId, "➖ ĐƯA NGƯỜI THUÊ RA KHỎI PHÒNG " + room.getRoomNumber()
                    + "\n\nChọn người thuê cần đưa ra khỏi phòng.",
                    new InlineKeyboardMarkup(rows));
            return;
        }
        boolean confirm = action.startsWith("ROOM_REMOVE_CONFIRM_");
        String prefix = confirm ? "ROOM_REMOVE_CONFIRM_" : "ROOM_REMOVE_SELECT_";
        if (!action.startsWith(prefix)) throw new BusinessException("Lựa chọn không hợp lệ.");
        String[] ids = action.substring(prefix.length()).split("_");
        if (ids.length != 2) throw new NumberFormatException();
        Long roomId = Long.parseLong(ids[0]);
        Long tenantId = Long.parseLong(ids[1]);
        Step expected = confirm ? Step.REMOVE_ROOM_TENANT_CONFIRM : Step.REMOVE_ROOM_TENANT_SELECT;
        if (state.getStep() != expected || !roomId.equals(state.getTargetRoomId())
                || confirm && !tenantId.equals(state.getTargetUserTelegramId())) {
            sender.sendNotice(chatId, "❌ Thao tác đã hết hiệu lực. Vui lòng mở lại chi tiết phòng để thực hiện.");
            return;
        }
        Room room = roomService.getRoomById(roomId);
        User tenant = roomService.getRoomTenants(roomId).stream()
                .filter(t -> tenantId.equals(t.getTelegramId())).findFirst()
                .orElseThrow(() -> new BusinessException("Người thuê không còn ở phòng đã chọn. Vui lòng kiểm tra lại."));
        Optional<Long> contractId = roomService.getMemberActiveContractId(tenantId);
        if (contractId.isPresent()) {
            state.setStep(Step.REMOVE_ROOM_TENANT_SELECT);
            state.setTargetUserTelegramId(null);
            stateService.setState(landlord.getTelegramId(), state);
            sender.sendMessageWithKeyboard(chatId, "⚠️ " + tenantName(tenant)
                    + " đang đứng tên hợp đồng còn hiệu lực. Cần kết thúc hợp đồng trước khi đưa người này ra khỏi phòng.",
                    new InlineKeyboardMarkup(List.of(
                            new InlineKeyboardRow(
                                    TelegramKeyboardUtil.createButton("📄 Xem hợp đồng", "CONTRACT_DETAIL_" + contractId.get())),
                            new InlineKeyboardRow(
                                    TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_ROOM_REMOVE_TENANT")))));
            return;
        }
        if (!confirm) {
            state.setTargetUserTelegramId(tenantId);
            state.setStep(Step.REMOVE_ROOM_TENANT_CONFIRM);
            stateService.setState(landlord.getTelegramId(), state);
            sender.sendMessageWithKeyboard(chatId, "⚠️ XÁC NHẬN ĐƯA NGƯỜI THUÊ RA KHỎI PHÒNG\n\nBạn có chắc muốn đưa "
                    + tenantName(tenant) + " (ID: " + tenantId + ") ra khỏi phòng " + room.getRoomNumber()
                    + "?\n\nTài khoản người thuê vẫn được giữ lại.",
                    TelegramKeyboardUtil.getConfirmKeyboard("ROOM_REMOVE_CONFIRM_" + roomId + "_" + tenantId, "CANCEL_ROOM_REMOVE_TENANT"));
            return;
        }
        roomService.removeMemberFromRoom(landlord.getTelegramId(), tenantId, roomId);
        stateService.clearState(landlord.getTelegramId());
        sender.sendNotice(chatId, "✅ Đã đưa " + tenantName(tenant) + " ra khỏi phòng " + room.getRoomNumber() + ".");
        showRemovalRoom(chatId, landlord, roomId, sender);
    }

    private String tenantName(User tenant) {
        return tenant.getFullName() != null ? tenant.getFullName()
                : tenant.getUsername() != null ? tenant.getUsername() : "Người thuê";
    }

    private void showRemovalRoom(Long chatId, User user, Long roomId, BotMessageSender sender) {
        try {
            showRoomDetail(chatId, user, roomId, null, null, 0, sender);
        } catch (BusinessException e) {
            sender.sendNotice(chatId, "❌ " + e.getMessage());
            sender.sendMessageWithKeyboard(chatId, "🏠 QUẢN LÝ PHÒNG", TelegramKeyboardUtil.getLandlordRoomManagementMenu());
        }
    }

    private void deleteRoom(Long chatId, User user, Long roomId, BotMessageSender messageSender) {
        try {
            roomService.deleteRoom(user.getTelegramId(), roomId);
            messageSender.sendResultAndMenu(chatId, "✅ Đã xóa phòng thành công.",
                    TelegramKeyboardUtil.getLandlordRoomManagementMenu());
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void showMemberRoom(Long chatId, User user, BotMessageSender messageSender) {
        Optional<Room> roomOpt = roomService.getMemberRoom(user.getTelegramId());

        if (roomOpt.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId,
                    "🏠 PHÒNG CỦA TÔI\n\nBạn hiện chưa được xếp vào phòng nào trong nhà trọ.", TelegramKeyboardUtil.getMemberMenu());
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
                """,
                room.getRoomNumber(),
                CURRENCY_FORMAT.format(room.getMonthlyRent()),
                occupants,
                room.getMaxOccupants(),
                cotenantsSb.toString().trim());

        memberText += "\n" + roomPublicDetails(room);
        messageSender.sendMessageWithKeyboard(chatId, memberText, TelegramKeyboardUtil.getMemberMenu());
    }
    private String roomPublicDetails(Room room) {
        return "📐 Diện tích: " + (room.getArea() == null ? "Chưa cập nhật" : room.getArea().stripTrailingZeros().toPlainString() + " m²")
                + "\n💵 Tiền cọc yêu cầu: " + (room.getRequiredDeposit() == null ? "Chưa cập nhật" : CURRENCY_FORMAT.format(room.getRequiredDeposit()))
                + "\n⚡ Giá điện: " + roomService.utilityPriceDisplay(com.boardinghousebot.domain.meter.UtilityType.ELECTRICITY)
                + "\n💧 Giá nước: " + roomService.utilityPriceDisplay(com.boardinghousebot.domain.meter.UtilityType.WATER);
    }

    private void showAvailableRooms(Long chatId, int requestedPage, BotMessageSender sender) {
        List<Room> rooms = roomService.getAvailableRooms().stream()
                .sorted(java.util.Comparator.comparing(Room::getRoomNumber)).toList();
        int pageSize = 5;
        int pages = Math.max(1, (rooms.size() + pageSize - 1) / pageSize);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        StringBuilder text = new StringBuilder("🏘️ PHÒNG CÒN CHỖ\n\n");
        if (rooms.isEmpty()) text.append("Hiện chưa có phòng còn chỗ.");
        for (Room room : rooms.subList(page * pageSize, Math.min((page + 1) * pageSize, rooms.size()))) {
            text.append("🏠 Phòng ").append(room.getRoomNumber())
                    .append("\n💰 Giá thuê/tháng: ").append(CURRENCY_FORMAT.format(room.getMonthlyRent()))
                    .append("\n👥 Số người đang ở: ").append(roomService.getCurrentOccupantCount(room.getId()))
                    .append("/").append(room.getMaxOccupants()).append("\n")
                    .append(roomPublicDetails(room)).append("\n\n");
        }
        var rows = new ArrayList<InlineKeyboardRow>();
        var nav = new InlineKeyboardRow();
        if (page > 0) nav.add(TelegramKeyboardUtil.createButton("⬅️ Trước", "MEMBER_AVAILABLE_ROOMS_" + (page - 1)));
        if (page + 1 < pages) nav.add(TelegramKeyboardUtil.createButton("Sau ➡️", "MEMBER_AVAILABLE_ROOMS_" + (page + 1)));
        if (!nav.isEmpty()) rows.add(nav);
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔄 Làm mới", "MEMBER_AVAILABLE_ROOMS_" + page),
                TelegramKeyboardUtil.createButton("🔙 Menu", "MAIN_MENU")));
        if (pages > 1) text.append("Trang ").append(page + 1).append("/").append(pages);
        sender.sendMessageWithKeyboard(chatId, text.toString().trim(), new InlineKeyboardMarkup(rows));
    }

}
