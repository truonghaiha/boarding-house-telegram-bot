package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.state.UserConversationState.Step;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.repository.ContractRepository;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class TenantCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantCommandHandler.class);
    private static final DecimalFormat CURRENCY_FORMAT = new DecimalFormat("#,###");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RoomService roomService;
    private final UserService userService;
    private final ConversationStateService stateService;
    private final ContractRepository contractRepository;

    public TenantCommandHandler(RoomService roomService, UserService userService, ConversationStateService stateService, ContractRepository contractRepository) {
        this.roomService = roomService;
        this.userService = userService;
        this.stateService = stateService;
        this.contractRepository = contractRepository;
    }

    @Override
    public String getCommand() {
        return "/tenant";
    }

    @Override
    public String getDescription() {
        return "Quản lý xếp người thuê vào phòng";
    }

    @Override
    public void handle(Update update, User user, BotMessageSender messageSender) {
        Long chatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        Long landlordTelegramId = user.getTelegramId();

        String text = extractText(update);
        if (text == null) return;

        UserConversationState state = stateService.getState(landlordTelegramId);

        if ("/cancel".equalsIgnoreCase(text) || "CANCEL_MOVE_TENANT".equals(text)) {
            stateService.clearState(landlordTelegramId);
            messageSender.sendMessageWithKeyboard(chatId, "❌ Đã hủy thao tác chuyển phòng.", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            if (text.startsWith("/assign")) {
                handleAssignCommand(chatId, text, landlordTelegramId, messageSender);
                return;
            }
            if (text.startsWith("/move")) {
                handleMoveCommand(chatId, text, landlordTelegramId, messageSender);
                return;
            }
            if (text.startsWith("/remove_tenant")) {
                handleRemoveCommand(chatId, text, landlordTelegramId, messageSender);
                return;
            }

            if (state.getStep() == Step.ASSIGN_TENANT_TELEGRAM_ID) {
                processAssignTenantInput(chatId, text, landlordTelegramId, state, messageSender);
                return;
            }
            if (state.getStep() == Step.MOVE_TENANT_TARGET_ROOM) {
                processMoveTenantInput(chatId, text, landlordTelegramId, state, messageSender);
                return;
            }
            if (state.getStep() == Step.MOVE_TENANT_MEMBER_TELEGRAM_ID) {
                processMoveTenantMemberIdInput(chatId, text, landlordTelegramId, state, messageSender);
                return;
            }
        }

        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();

            if ("LANDLORD_TENANTS".equals(data)) {
                messageSender.sendMessageWithKeyboard(chatId, "👥 QUẢN LÝ NGƯỜI THUÊ\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
                return;
            }
            if ("TENANT_LIST_ALL".equals(data)) {
                listAllTenants(chatId, user, messageSender);
                return;
            }
            if ("TENANT_ADD_INPUT".equals(data)) {
                messageSender.sendMessage(chatId, "➕ THÊM NGƯỜI THUÊ VÀO PHÒNG\n\nVui lòng nhập lệnh dạng:\n`/assign <Telegram_ID> <Số_Phòng>`\n\nVí dụ: `/assign 8167463325 101`");
                return;
            }
            if ("TENANT_REMOVE_INPUT".equals(data)) {
                messageSender.sendMessage(chatId, "➖ XÓA NGƯỜI THUÊ KHỎI PHÒNG\n\nVui lòng nhập lệnh dạng:\n`/remove_tenant <Telegram_ID>`\n\nVí dụ: `/remove_tenant 8167463325`");
                return;
            }
            if ("TENANT_MOVE_INPUT".equals(data)) {
                startMoveTenantWizard(chatId, landlordTelegramId, state, messageSender);
                return;
            }
            if (data.startsWith("SELECT_MOVE_TARGET_ROOM_")) {
                Long targetRoomId = Long.parseLong(data.replace("SELECT_MOVE_TARGET_ROOM_", "").trim());
                handleTargetRoomSelectedForMove(chatId, landlordTelegramId, targetRoomId, state, messageSender);
                return;
            }
            if ("CONFIRM_MOVE_TENANT".equals(data)) {
                executeMoveTenant(chatId, landlordTelegramId, state, messageSender);
                return;
            }
            if (data.startsWith("ROOM_ASSIGN_")) {
                Long roomId = Long.parseLong(data.substring("ROOM_ASSIGN_".length()));
                UserConversationState newState = new UserConversationState(Step.ASSIGN_TENANT_TELEGRAM_ID);
                newState.setTargetRoomId(roomId);
                stateService.setState(landlordTelegramId, newState);
                messageSender.sendMessage(chatId, "➕ Nhập Telegram ID của người thuê (MEMBER) cần xếp vào phòng:\n\n*(Gõ /cancel để hủy)*");
                return;
            }
            if (data.startsWith("ROOM_MOVE_")) {
                Long roomId = Long.parseLong(data.substring("ROOM_MOVE_".length()));
                UserConversationState newState = new UserConversationState(Step.MOVE_TENANT_TARGET_ROOM);
                newState.setTargetRoomId(roomId);
                stateService.setState(landlordTelegramId, newState);
                messageSender.sendMessage(chatId, "🚚 ĐỂ CHUYỂN PHÒNG:\n\nVui lòng nhập lệnh dạng:\n`/move <Telegram_ID_Người_Thuê> <Số_Phòng_Đích>`\n\nVí dụ: `/move 8167463325 102`");
                return;
            }
            if (data.startsWith("REMOVE_TENANT_")) {
                Long targetTelegramId = Long.parseLong(data.substring("REMOVE_TENANT_".length()));
                removeTenant(chatId, landlordTelegramId, targetTelegramId, messageSender);
                return;
            }
            if (data.startsWith("ROOM_TENANTS_")) {
                Long roomId = Long.parseLong(data.substring("ROOM_TENANTS_".length()));
                listRoomTenants(chatId, user, roomId, messageSender);
                return;
            }
        }

        messageSender.sendMessageWithKeyboard(chatId, "👥 QUẢN LÝ NGƯỜI THUÊ\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
    }

    private void startMoveTenantWizard(Long chatId, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        state.setStep(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID);
        stateService.setState(landlordTelegramId, state);

        messageSender.sendMessage(chatId, "🔄 CHUYỂN PHÒNG\n\nNhập Telegram ID của người thuê:\n\n/cancel");
    }

    private void processMoveTenantMemberIdInput(Long chatId, String input, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long targetTelegramId = Long.parseLong(input.trim());
            log.info("MOVE_TENANT Step 1: Searching for member with Telegram ID={}", targetTelegramId);

            User targetUser = userService.findByTelegramIdWithRoom(targetTelegramId)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng với Telegram ID " + targetTelegramId + "."));
            log.info("MOVE_TENANT Step 1 Success: Found user ID={}, username={}, fullName={}", targetUser.getId(), targetUser.getUsername(), targetUser.getFullName());

            log.info("MOVE_TENANT Step 2: Validating role for user Telegram ID={}", targetTelegramId);
            if (targetUser.getRole() != Role.MEMBER) {
                log.warn("MOVE_TENANT Step 2 Failed: User role is {}", targetUser.getRole());
                throw new BusinessException("Chỉ MEMBER mới có thể được chuyển phòng.");
            }
            log.info("MOVE_TENANT Step 2 Success: User role is MEMBER");

            log.info("MOVE_TENANT Step 3: Getting current room for user Telegram ID={}", targetTelegramId);
            Room currentRoom = targetUser.getRoom();
            if (currentRoom == null) {
                log.warn("MOVE_TENANT Step 3 Failed: User Telegram ID={} has no assigned room", targetTelegramId);
                throw new BusinessException("Người dùng này hiện chưa thuộc phòng nào.");
            }
            log.info("MOVE_TENANT Step 3 Success: Current room ID={}, RoomNumber={}", currentRoom.getId(), currentRoom.getRoomNumber());

            log.info("MOVE_TENANT Step 4: Getting available target rooms (excluding current room ID={})", currentRoom.getId());
            List<Room> allRooms = roomService.getAllRooms();
            List<Room> availableTargetRooms = allRooms.stream()
                    .filter(r -> !r.getId().equals(currentRoom.getId()))
                    .filter(r -> r.hasAvailableCapacity(roomService.getCurrentOccupantCount(r.getId())))
                    .toList();
            log.info("MOVE_TENANT Step 4 Success: Found {} available target rooms", availableTargetRooms.size());

            if (availableTargetRooms.isEmpty()) {
                log.warn("MOVE_TENANT Step 4 Failed: No available target rooms found");
                stateService.clearState(landlordTelegramId);
                messageSender.sendMessageWithKeyboard(chatId, "❌ Không có phòng khác đủ điều kiện (trống chỗ) để chuyển.", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
                return;
            }

            state.getData().put("targetUserTelegramId", targetTelegramId);
            state.getData().put("targetUserFullName", targetUser.getFullName() != null ? targetUser.getFullName() : targetUser.getUsername());
            state.getData().put("oldRoomId", currentRoom.getId());
            state.getData().put("oldRoomNumber", currentRoom.getRoomNumber());
            state.setStep(Step.MOVE_TENANT_TARGET_ROOM_SELECT);
            stateService.setState(landlordTelegramId, state);

            log.info("MOVE_TENANT Step 5: Creating inline keyboard for target rooms");
            List<InlineKeyboardRow> rows = new ArrayList<>();
            for (Room r : availableTargetRooms) {
                int occ = roomService.getCurrentOccupantCount(r.getId());
                String label = String.format("🏠 %s - %sđ - %d/%d", r.getRoomNumber(), CURRENCY_FORMAT.format(r.getMonthlyRent()), occ, r.getMaxOccupants());
                rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "SELECT_MOVE_TARGET_ROOM_" + r.getId())));
            }
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_MOVE_TENANT")));
            log.info("MOVE_TENANT Step 5 Success: Created inline keyboard with {} rows", rows.size());

            String promptMsg = String.format("""
                    🔄 THÔNG TIN CHUYỂN PHÒNG

                    Người thuê: %s
                    Telegram ID: %d

                    Phòng hiện tại: %s

                    Chọn phòng mới:
                    """,
                    targetUser.getFullName() != null ? targetUser.getFullName() : targetUser.getUsername(),
                    targetTelegramId,
                    currentRoom.getRoomNumber()
            );

            log.info("MOVE_TENANT Step 6: Sending message with inline keyboard to chatId={}", chatId);
            messageSender.sendMessageWithKeyboard(chatId, promptMsg, new InlineKeyboardMarkup(rows));
            log.info("MOVE_TENANT Step 6 Success: Message sent successfully");

        } catch (NumberFormatException e) {
            log.warn("MOVE_TENANT Error: Invalid Telegram ID format '{}'", input);
            messageSender.sendMessage(chatId, "❌ Telegram ID phải là một số nguyên hợp lệ. Vui lòng nhập lại:");
        } catch (BusinessException e) {
            log.warn("MOVE_TENANT BusinessException: {}", e.getMessage());
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("MOVE_TENANT Unexpected Exception", e);
            messageSender.sendMessage(chatId, "❌ Đã xảy ra lỗi hệ thống: " + e.getMessage());
        }
    }

    private void handleTargetRoomSelectedForMove(Long chatId, Long landlordTelegramId, Long targetRoomId, UserConversationState state, BotMessageSender messageSender) {
        try {
            Room targetRoom = roomService.getRoomById(targetRoomId);
            String fullName = (String) state.getData().get("targetUserFullName");
            Long targetTelegramId = (Long) state.getData().get("targetUserTelegramId");
            String oldRoomNumber = (String) state.getData().get("oldRoomNumber");

            state.getData().put("targetRoomId", targetRoomId);
            state.setStep(Step.MOVE_TENANT_CONFIRM);
            stateService.setState(landlordTelegramId, state);

            int currentOcc = roomService.getCurrentOccupantCount(targetRoomId);
            String rentStr = CURRENCY_FORMAT.format(targetRoom.getMonthlyRent());

            Optional<Contract> activeContractOpt = contractRepository.findActiveContractByMemberTelegramId(targetTelegramId);
            StringBuilder sb = new StringBuilder();
            sb.append("🔄 XÁC NHẬN CHUYỂN PHÒNG\n\n");
            sb.append("👤 Người thuê: ").append(fullName).append("\n");
            sb.append("🆔 Telegram ID: ").append(targetTelegramId).append("\n\n");
            sb.append("🏠 Phòng hiện tại: ").append(oldRoomNumber).append("\n");
            sb.append("➡️ Phòng mới: ").append(targetRoom.getRoomNumber()).append("\n\n");

            if (activeContractOpt.isPresent()) {
                Contract oldC = activeContractOpt.get();
                sb.append("📄 Hợp đồng hiện tại: #").append(oldC.getId()).append("\n");
                sb.append("➡️ Sẽ kết thúc: ").append(LocalDate.now().format(DATE_FORMATTER)).append("\n\n");
                sb.append("📄 Hợp đồng mới: sẽ tạo cho phòng ").append(targetRoom.getRoomNumber()).append("\n\n");
            }

            sb.append("💰 Tiền phòng mới:\n").append(rentStr).append(" VNĐ/tháng\n\n");
            sb.append("👥 Số người:\n").append(currentOcc + 1).append("/").append(targetRoom.getMaxOccupants()).append("\n\n");
            sb.append("Bạn có chắc chắn muốn chuyển?");

            String confirmMsg = sb.toString();

            messageSender.sendMessageWithKeyboard(chatId, confirmMsg, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_MOVE_TENANT", "CANCEL_MOVE_TENANT"));

        } catch (BusinessException e) {
            stateService.clearState(landlordTelegramId);
            messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordTenantManagementMenu());
        }
    }

    private void executeMoveTenant(Long chatId, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long targetTelegramId = (Long) state.getData().get("targetUserTelegramId");
            Long targetRoomId = (Long) state.getData().get("targetRoomId");

            User movedUser = roomService.moveMemberToRoom(landlordTelegramId, targetTelegramId, targetRoomId);
            stateService.clearState(landlordTelegramId);

            messageSender.sendMessageWithKeyboard(chatId, "✅ Đã chuyển người thuê " + movedUser.getFullName() + " sang phòng " + movedUser.getRoom().getRoomNumber() + " thành công!", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(landlordTelegramId);
            messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordTenantManagementMenu());
        }
    }

    private void handleAssignCommand(Long chatId, String text, Long landlordTelegramId, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 3) {
                messageSender.sendMessage(chatId, "Cú pháp không đúng. Vui lòng nhập: `/assign <Telegram_ID> <Số_Phòng>`");
                return;
            }
            Long targetTelegramId = Long.parseLong(parts[1]);
            String roomNumber = parts[2].trim();

            Room room = roomService.getRoomByRoomNumber(roomNumber)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy phòng " + roomNumber + "."));

            User assignedUser = roomService.assignMemberToRoom(landlordTelegramId, targetTelegramId, room.getId());
            messageSender.sendMessage(chatId, "✅ Đã xếp người thuê " + assignedUser.getFullName() + " (ID: " + targetTelegramId + ") vào phòng " + room.getRoomNumber() + " thành công!");
        } catch (NumberFormatException e) {
            messageSender.sendMessage(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void processAssignTenantInput(Long chatId, String input, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long targetTelegramId = Long.parseLong(input);
            Long roomId = state.getTargetRoomId();

            User assignedUser = roomService.assignMemberToRoom(landlordTelegramId, targetTelegramId, roomId);
            stateService.clearState(landlordTelegramId);

            messageSender.sendMessage(chatId, "✅ Đã xếp người thuê " + assignedUser.getFullName() + " (ID: " + targetTelegramId + ") vào phòng thành công!");
        } catch (NumberFormatException e) {
            messageSender.sendMessage(chatId, "❌ Telegram ID phải là một số nguyên hợp lệ. Vui lòng nhập lại:");
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void handleMoveCommand(Long chatId, String text, Long landlordTelegramId, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 3) {
                messageSender.sendMessage(chatId, "Cú pháp không đúng. Vui lòng nhập: `/move <Telegram_ID> <Số_Phòng_Đích>`");
                return;
            }
            Long targetTelegramId = Long.parseLong(parts[1]);
            String targetRoomNumber = parts[2].trim();

            Room targetRoom = roomService.getRoomByRoomNumber(targetRoomNumber)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy phòng " + targetRoomNumber + "."));

            User movedUser = roomService.moveMemberToRoom(landlordTelegramId, targetTelegramId, targetRoom.getId());
            messageSender.sendMessage(chatId, "✅ Đã chuyển người thuê " + movedUser.getFullName() + " sang phòng " + targetRoom.getRoomNumber() + " thành công!");
        } catch (NumberFormatException e) {
            messageSender.sendMessage(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void processMoveTenantInput(Long chatId, String input, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        handleMoveCommand(chatId, input, landlordTelegramId, messageSender);
        stateService.clearState(landlordTelegramId);
    }

    private void handleRemoveCommand(Long chatId, String text, Long landlordTelegramId, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                messageSender.sendMessage(chatId, "Cú pháp không đúng. Vui lòng nhập: `/remove_tenant <Telegram_ID>`");
                return;
            }
            Long targetTelegramId = Long.parseLong(parts[1]);
            removeTenant(chatId, landlordTelegramId, targetTelegramId, messageSender);
        } catch (NumberFormatException e) {
            messageSender.sendMessage(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        }
    }

    private void removeTenant(Long chatId, Long landlordTelegramId, Long targetTelegramId, BotMessageSender messageSender) {
        try {
            User removedUser = roomService.removeMemberFromRoom(landlordTelegramId, targetTelegramId);
            messageSender.sendMessage(chatId, "✅ Đã xóa người thuê " + removedUser.getFullName() + " (ID: " + targetTelegramId + ") khỏi phòng thành công!");
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
        }
    }

    private void listAllTenants(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD && user.getRole() != Role.ADMIN) {
            messageSender.sendMessage(chatId, "Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<User> members = userService.getUsersByRoleWithRoom(Role.MEMBER);
        List<User> assignedMembers = members.stream().filter(m -> m.getRoom() != null).toList();

        if (assignedMembers.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "👥 DANH SÁCH NGƯỜI THUÊ\n\nChưa có người thuê nào được xếp vào phòng.", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
            return;
        }

        StringBuilder sb = new StringBuilder("👥 DANH SÁCH NGƯỜI THUÊ (" + assignedMembers.size() + " người)\n\n");
        int index = 1;
        for (User u : assignedMembers) {
            String roomNum = u.getRoom() != null ? u.getRoom().getRoomNumber() : "Chưa xếp phòng";
            sb.append(index++).append(". ").append(u.getFullName() != null ? u.getFullName() : "MEMBER").append("\n");
            sb.append("   📱 Telegram ID: ").append(u.getTelegramId()).append("\n");
            sb.append("   🏠 Phòng: ").append(roomNum).append("\n");
            sb.append("   Lệnh xóa: `/remove_tenant ").append(u.getTelegramId()).append("`\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getLandlordTenantManagementMenu());
    }

    private void listRoomTenants(Long chatId, User user, Long roomId, BotMessageSender messageSender) {
        Room room = roomService.getRoomById(roomId);
        List<User> tenants = roomService.getRoomTenants(roomId);

        if (tenants == null || tenants.isEmpty()) {
            messageSender.sendMessage(chatId, "👥 NGƯỜI THUÊ PHÒNG " + room.getRoomNumber() + "\n\nPhòng hiện chưa có người ở.");
            return;
        }

        StringBuilder sb = new StringBuilder("👥 NGƯỜI THUÊ PHÒNG " + room.getRoomNumber() + "\n\n");
        int index = 1;
        for (User u : tenants) {
            sb.append(index++).append(". ").append(u.getFullName() != null ? u.getFullName() : "Tenant").append("\n");
            sb.append("   📱 Telegram ID: ").append(u.getTelegramId()).append("\n");
            sb.append("   Username: ").append(u.getUsername() != null ? u.getUsername() : "Chưa đặt").append("\n\n");
        }

        messageSender.sendMessage(chatId, sb.toString().trim());
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
