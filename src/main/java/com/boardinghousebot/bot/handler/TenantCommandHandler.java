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
        String action = update.hasMessage() ? update.getMessage().getText() : update.getCallbackQuery().getData();
        Long actionChatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        var actionStep = stateService.getState(user.getTelegramId()).getStep();
        if (!com.boardinghousebot.bot.command.RoleActionPolicy.allows(user, getCommand(), action, actionStep, update.hasMessage())) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(actionChatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        Long chatId = update.hasMessage() ? update.getMessage().getChatId() : update.getCallbackQuery().getMessage().getChatId();
        Long landlordTelegramId = user.getTelegramId();

        String text = extractText(update);
        if (text == null) return;

        UserConversationState state = stateService.getState(landlordTelegramId);

        if ("/cancel".equalsIgnoreCase(text) || "CANCEL_MOVE_TENANT".equals(text) || "TENANT_EDIT_CANCEL".equals(text)) {
            boolean isAssign = state.getStep().name().startsWith("ASSIGN_TENANT");
            stateService.clearState(landlordTelegramId);
            String cancelMessage = isAssign ? "❌ Đã hủy thao tác thêm người thuê vào phòng."
                    : state.getStep().name().startsWith("EDIT_TENANT") ? "❌ Đã hủy thao tác sửa thông tin người thuê."
                    : state.getStep().name().startsWith("MOVE_TENANT") ? "❌ Đã hủy thao tác chuyển phòng."
                    : "❌ Đã hủy thao tác quản lý người thuê.";
            messageSender.sendMessageWithKeyboard(chatId, cancelMessage, TelegramKeyboardUtil.getLandlordTenantManagementMenu());
            return;
        }

        if (update.hasMessage() && update.getMessage().hasText()) {
            if (state.getStep() == Step.REMOVE_TENANT_INPUT) {
                try { promptRemoveTenant(chatId, landlordTelegramId, Long.parseLong(text.trim()), messageSender); }
                catch (NumberFormatException e) { messageSender.sendNotice(chatId, "❌ Telegram ID phải là một số nguyên hợp lệ. Vui lòng nhập lại."); }
                return;
            }
            if (state.getStep() == Step.REMOVE_TENANT_CONFIRM || state.getStep() == Step.ASSIGN_TENANT_ROOM_SELECT) {
                messageSender.sendNotice(chatId, "Vui lòng chọn bằng nút bên dưới hoặc Hủy.");
                return;
            }
            if (state.getStep() == Step.ASSIGN_TENANT_PHONE || state.getStep().name().startsWith("EDIT_TENANT")) {
                processTenantContactInput(chatId, text, landlordTelegramId, state, messageSender);
                return;
            }
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
            if (data.startsWith("TENANT_ASSIGN_ROOM_")) {
                if (state.getStep() != Step.ASSIGN_TENANT_ROOM_SELECT) {
                    messageSender.sendNotice(chatId, "❌ Lựa chọn đã hết hiệu lực. Vui lòng bắt đầu lại."); return;
                }
                String[] ids = data.substring("TENANT_ASSIGN_ROOM_".length()).split("_");
                try {
                    if (ids.length != 2 || !Long.valueOf(ids[1]).equals(state.getTargetUserTelegramId()))
                        throw new BusinessException("Lựa chọn đã hết hiệu lực. Vui lòng chọn lại.");
                    startAssignPhone(chatId, landlordTelegramId, state.getTargetUserTelegramId(), Long.parseLong(ids[0]), messageSender);
                } catch (NumberFormatException | BusinessException e) { messageSender.sendNotice(chatId, "❌ Lựa chọn phòng không hợp lệ hoặc phòng không còn chỗ. Vui lòng chọn lại."); }
                return;
            }
            if (data.startsWith("TENANT_REMOVE_CONFIRM_")) {
                String expected = "TENANT_REMOVE_CONFIRM_" + state.getTargetRoomId() + "_" + state.getTargetUserTelegramId();
                if (state.getStep() != Step.REMOVE_TENANT_CONFIRM || !expected.equals(data)) {
                    messageSender.sendNotice(chatId, "❌ Thao tác đã hết hiệu lực. Vui lòng bắt đầu lại."); return;
                }
                try {
                    User removed = roomService.removeMemberFromRoom(landlordTelegramId, state.getTargetUserTelegramId(), state.getTargetRoomId());
                    stateService.clearState(landlordTelegramId);
                    messageSender.sendResultAndMenu(chatId, "✅ Đã xóa người thuê " + removed.getFullName() + " khỏi phòng.", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
                } catch (BusinessException e) {
                    stateService.clearState(landlordTelegramId);
                    messageSender.sendNotice(chatId, "❌ " + e.getMessage());
                }
                return;
            }

            if ("TENANT_EDIT_INPUT".equals(data)) {
                stateService.setState(landlordTelegramId, new UserConversationState(Step.EDIT_TENANT_TELEGRAM_ID));
                messageSender.sendPrompt(chatId, "✏️ SỬA THÔNG TIN NGƯỜI THUÊ\n\nNhập Telegram ID của người thuê cần sửa số điện thoại:");
                return;
            }
            if ("TENANT_EDIT_CONFIRM".equals(data)) {
                if (state.getStep() != Step.EDIT_TENANT_CONFIRM) {
                    messageSender.sendNotice(chatId, "❌ Thao tác đã hết hiệu lực. Vui lòng bắt đầu sửa lại.");
                    return;
                }
                try {
                    User saved = userService.updateTenantPhoneNumber(landlordTelegramId, state.getTargetUserTelegramId(), (String) state.getData().get("phone"));
                    stateService.clearState(landlordTelegramId);
                    messageSender.sendNotice(chatId, "✅ Đã cập nhật số điện thoại của " + saved.getFullName() + ": " + saved.getPhoneNumberDisplay());
                    listAllTenants(chatId, user, messageSender);
                } catch (BusinessException e) {
                    stateService.clearState(landlordTelegramId);
                    messageSender.sendNotice(chatId, "❌ " + e.getMessage());
                }
                return;
            }

            if ("LANDLORD_TENANTS".equals(data)) {
                messageSender.sendMessageWithKeyboard(chatId, "👥 QUẢN LÝ NGƯỜI THUÊ\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
                return;
            }
            if ("TENANT_LIST_ALL".equals(data)) {
                listAllTenants(chatId, user, messageSender);
                return;
            }
            if ("TENANT_ADD_INPUT".equals(data)) {
                stateService.setState(landlordTelegramId, new UserConversationState(Step.ASSIGN_TENANT_TELEGRAM_ID));
                messageSender.sendPrompt(chatId, "➕ THÊM NGƯỜI THUÊ\n\nNhập Telegram ID của người thuê cần thêm:");
                return;
            }
            if ("TENANT_REMOVE_INPUT".equals(data)) {
                stateService.setState(landlordTelegramId, new UserConversationState(Step.REMOVE_TENANT_INPUT));
                messageSender.sendPrompt(chatId, "➖ XÓA NGƯỜI THUÊ KHỎI PHÒNG\n\nNhập Telegram ID của người thuê cần xóa:");
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
                messageSender.sendPrompt(chatId, "➕ Nhập Telegram ID của người thuê (MEMBER) cần xếp vào phòng:");
                return;
            }
            if (data.startsWith("ROOM_MOVE_")) {
                Long roomId = Long.parseLong(data.substring("ROOM_MOVE_".length()));
                UserConversationState newState = new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID);
                newState.getData().put("sourceRoomId", roomId);
                stateService.setState(landlordTelegramId, newState);
                messageSender.sendPrompt(chatId, "🚚 CHUYỂN PHÒNG\n\nNhập Telegram ID của người thuê trong phòng này cần chuyển:");
                return;
            }
            if (data.startsWith("REMOVE_TENANT_")) {
                Long targetTelegramId = Long.parseLong(data.substring("REMOVE_TENANT_".length()));
                promptRemoveTenant(chatId, landlordTelegramId, targetTelegramId, messageSender);
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
        state = new UserConversationState(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID);
        stateService.setState(landlordTelegramId, state);

        messageSender.sendPrompt(chatId, "🔄 CHUYỂN PHÒNG\n\nNhập Telegram ID của người thuê:");
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
            if (state.getData().get("sourceRoomId") != null && !currentRoom.getId().equals(state.getData().get("sourceRoomId")))
                throw new BusinessException("Người thuê không thuộc phòng đã chọn. Vui lòng nhập lại ID.");
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
            messageSender.sendNotice(chatId, "❌ Telegram ID phải là một số nguyên hợp lệ. Vui lòng nhập lại:");
        } catch (BusinessException e) {
            log.warn("MOVE_TENANT BusinessException: {}", e.getMessage());
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        } catch (Exception e) {
            log.error("MOVE_TENANT Unexpected Exception", e);
            messageSender.sendMessage(chatId, "❌ Đã xảy ra lỗi hệ thống: " + e.getMessage());
        }
    }

    private void handleTargetRoomSelectedForMove(Long chatId, Long landlordTelegramId, Long targetRoomId, UserConversationState state, BotMessageSender messageSender) {
        if (state.getStep() != Step.MOVE_TENANT_TARGET_ROOM_SELECT) {
            messageSender.sendNotice(chatId, "❌ Lựa chọn phòng đã hết hiệu lực. Vui lòng bắt đầu lại."); return;
        }
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
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void executeMoveTenant(Long chatId, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        if (state.getStep() != Step.MOVE_TENANT_CONFIRM) {
            messageSender.sendNotice(chatId, "❌ Thao tác đã hết hiệu lực. Vui lòng bắt đầu lại."); return;
        }
        try {
            Long targetTelegramId = (Long) state.getData().get("targetUserTelegramId");
            Long targetRoomId = (Long) state.getData().get("targetRoomId");

            User movedUser = roomService.moveMemberToRoom(landlordTelegramId, targetTelegramId, targetRoomId);
            stateService.clearState(landlordTelegramId);

            messageSender.sendResultAndMenu(chatId, "✅ Đã chuyển người thuê " + movedUser.getFullName() + " sang phòng " + movedUser.getRoom().getRoomNumber() + " thành công!", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(landlordTelegramId);
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void handleAssignCommand(Long chatId, String text, Long landlordTelegramId, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 3) {
                stateService.setState(landlordTelegramId, new UserConversationState(Step.ASSIGN_TENANT_TELEGRAM_ID));
                messageSender.sendPrompt(chatId, "Nhập Telegram ID của người thuê cần thêm:");
                return;
            }
            Long targetTelegramId = Long.parseLong(parts[1]);
            String roomNumber = parts[2].trim();

            Room room = roomService.getRoomByRoomNumber(roomNumber)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy phòng " + roomNumber + "."));

            startAssignPhone(chatId, landlordTelegramId, targetTelegramId, room.getId(), messageSender);
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void processAssignTenantInput(Long chatId, String input, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long targetTelegramId = Long.parseLong(input);
            Long roomId = state.getTargetRoomId();
            if (roomId == null) {
                User tenant = userService.findByTelegramIdWithRoom(targetTelegramId)
                        .orElseThrow(() -> new BusinessException("Không tìm thấy người thuê. Vui lòng nhập lại ID."));
                if (tenant.getRole() != Role.MEMBER)
                    throw new BusinessException("Chỉ có thể thêm người dùng có vai trò MEMBER vào phòng. Vui lòng nhập lại ID.");
                if (tenant.getRoom() != null)
                    throw new BusinessException("Người này đã thuê phòng, không thể thêm vào phòng mới.");
                List<Room> available = roomService.getAvailableRooms();
                if (available.isEmpty()) { messageSender.sendNotice(chatId, "❌ Không có phòng còn chỗ. Bạn có thể hủy thao tác."); return; }
                state.setTargetUserTelegramId(targetTelegramId);
                state.setStep(Step.ASSIGN_TENANT_ROOM_SELECT);
                stateService.setState(landlordTelegramId, state);
                List<InlineKeyboardRow> rows = new ArrayList<>();
                for (Room room : available) rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🏠 Phòng " + room.getRoomNumber(),
                        "TENANT_ASSIGN_ROOM_" + room.getId() + "_" + targetTelegramId)));
                rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "/cancel")));
                messageSender.sendMessageWithKeyboard(chatId, "🏠 Chọn phòng cho " + tenant.getFullName() + ":", new InlineKeyboardMarkup(rows));
            } else startAssignPhone(chatId, landlordTelegramId, targetTelegramId, roomId, messageSender);
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "❌ Telegram ID phải là một số nguyên hợp lệ. Vui lòng nhập lại:");
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void startAssignPhone(Long chatId, Long landlordId, Long tenantId, Long roomId, BotMessageSender sender) {
        User tenant = userService.findByTelegramIdWithRoom(tenantId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người thuê. Người thuê cần gửi /start cho bot trước."));
        if (tenant.getRole() != Role.MEMBER) throw new BusinessException("Chỉ MEMBER mới có thể được xếp vào phòng.");
        if (tenant.getRoom() != null) throw new BusinessException("Người này đã thuê phòng, không thể thêm vào phòng mới.");
        Room room = roomService.getRoomById(roomId);
        if (roomService.isRoomFull(room)) throw new BusinessException("Phòng đã đủ số người.");
        UserConversationState next = new UserConversationState(Step.ASSIGN_TENANT_PHONE);
        next.setTargetRoomId(roomId);
        next.setTargetUserTelegramId(tenantId);
        stateService.setState(landlordId, next);
        sender.sendPrompt(chatId, "📞 Nhập số điện thoại liên hệ của " + tenant.getFullName()
                + " (bắt buộc) để hoàn tất thêm vào phòng " + room.getRoomNumber()
                + ":\n\nVí dụ: 0912345678 hoặc +84912345678.");
    }

    private void processTenantContactInput(Long chatId, String text, Long landlordId, UserConversationState state, BotMessageSender sender) {
        try {
            if (state.getStep() == Step.EDIT_TENANT_TELEGRAM_ID) {
                Long tenantId = Long.parseLong(text.trim());
                User tenant = userService.findByTelegramIdWithRoom(tenantId)
                        .orElseThrow(() -> new BusinessException("Không tìm thấy người thuê. Vui lòng nhập lại Telegram ID."));
                if (tenant.getRole() != Role.MEMBER || tenant.getRoom() == null)
                    throw new BusinessException("Người này không phải người thuê đang được xếp phòng. Vui lòng nhập lại ID.");
                state.setTargetUserTelegramId(tenantId);
                state.setTargetRoomId(tenant.getRoom().getId());
                state.setStep(Step.EDIT_TENANT_PHONE);
                stateService.setState(landlordId, state);
                sender.sendPrompt(chatId, "✏️ " + tenant.getFullName() + " (ID: " + tenantId + ")\n📞 Số điện thoại hiện tại: "
                        + tenant.getPhoneNumberDisplay() + "\n\nNhập số điện thoại mới (bắt buộc):\nVí dụ: 0912345678 hoặc +84912345678.");
            } else if (state.getStep() == Step.EDIT_TENANT_CONFIRM) {
                sender.sendNotice(chatId, "Vui lòng bấm Xác nhận để lưu.");
            } else {
                String phone = com.boardinghousebot.util.PhoneNumberUtil.normalizeRequired(text);
                if (state.getStep() == Step.ASSIGN_TENANT_PHONE) {
                    User assigned = roomService.assignMemberToRoom(landlordId, state.getTargetUserTelegramId(), state.getTargetRoomId(), phone);
                    stateService.clearState(landlordId);
                    sender.sendResultAndMenu(chatId, "✅ Đã xếp người thuê " + assigned.getFullName()
                            + " vào phòng thành công!\n📞 Số điện thoại: " + assigned.getPhoneNumberDisplay(), TelegramKeyboardUtil.getLandlordTenantManagementMenu());
                } else {
                    User tenant = userService.findByTelegramIdWithRoom(state.getTargetUserTelegramId())
                            .orElseThrow(() -> new BusinessException("Không tìm thấy người thuê."));
                    state.getData().put("phone", phone);
                    state.setStep(Step.EDIT_TENANT_CONFIRM);
                    stateService.setState(landlordId, state);
                    sender.sendMessageWithKeyboard(chatId, "✏️ XÁC NHẬN SỬA SỐ ĐIỆN THOẠI\n\n👤 " + tenant.getFullName()
                            + " (ID: " + tenant.getTelegramId() + ")\n📞 Hiện tại: " + tenant.getPhoneNumberDisplay()
                            + "\n📞 Số mới: " + phone + "",
                            TelegramKeyboardUtil.getConfirmKeyboard("TENANT_EDIT_CONFIRM", "/cancel"));
                }
            }
        } catch (NumberFormatException e) {
            sender.sendNotice(chatId, "❌ Telegram ID phải là số nguyên hợp lệ. Vui lòng nhập lại.");
        } catch (BusinessException e) {
            sender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void handleMoveCommand(Long chatId, String text, Long landlordTelegramId, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 3) {
                startMoveTenantWizard(chatId, landlordTelegramId, stateService.getState(landlordTelegramId), messageSender);
                return;
            }
            Long targetTelegramId = Long.parseLong(parts[1]);
            String targetRoomNumber = parts[2].trim();

            Room targetRoom = roomService.getRoomByRoomNumber(targetRoomNumber)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy phòng " + targetRoomNumber + "."));

            User movedUser = roomService.moveMemberToRoom(landlordTelegramId, targetTelegramId, targetRoom.getId());
            messageSender.sendResultAndMenu(chatId, "✅ Đã chuyển người thuê " + movedUser.getFullName() + " sang phòng " + targetRoom.getRoomNumber() + " thành công!", TelegramKeyboardUtil.getLandlordTenantManagementMenu());
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void processMoveTenantInput(Long chatId, String input, Long landlordTelegramId, UserConversationState state, BotMessageSender messageSender) {
        if (state.getTargetRoomId() != null) state.getData().put("sourceRoomId", state.getTargetRoomId());
        processMoveTenantMemberIdInput(chatId, input, landlordTelegramId, state, messageSender);
    }

    private void handleRemoveCommand(Long chatId, String text, Long landlordTelegramId, BotMessageSender messageSender) {
        try {
            String[] parts = text.split("\\s+");
            if (parts.length < 2) {
                stateService.setState(landlordTelegramId, new UserConversationState(Step.REMOVE_TENANT_INPUT));
                messageSender.sendPrompt(chatId, "Nhập Telegram ID của người thuê cần xóa khỏi phòng:");
                return;
            }
            Long targetTelegramId = Long.parseLong(parts[1]);
            promptRemoveTenant(chatId, landlordTelegramId, targetTelegramId, messageSender);
        } catch (NumberFormatException e) {
            messageSender.sendNotice(chatId, "Telegram ID phải là một số nguyên hợp lệ.");
        }
    }

    private void promptRemoveTenant(Long chatId, Long landlordTelegramId, Long targetTelegramId, BotMessageSender messageSender) {
        try {
            User tenant = userService.findByTelegramIdWithRoom(targetTelegramId)
                    .orElseThrow(() -> new BusinessException("Không tìm thấy người thuê. Vui lòng nhập lại ID."));
            if (tenant.getRole() != Role.MEMBER || tenant.getRoom() == null)
                throw new BusinessException("Người này chưa được xếp phòng. Vui lòng nhập lại ID.");
            if (contractRepository.findActiveContractByMemberTelegramId(targetTelegramId).isPresent())
                throw new BusinessException("Cần kết thúc hợp đồng đang hiệu lực của người đại diện trước khi xóa khỏi phòng.");
            var next = new UserConversationState(Step.REMOVE_TENANT_CONFIRM);
            next.setTargetUserTelegramId(targetTelegramId); next.setTargetRoomId(tenant.getRoom().getId());
            stateService.setState(landlordTelegramId, next);
            messageSender.sendMessageWithKeyboard(chatId, "⚠️ XÁC NHẬN XÓA NGƯỜI THUÊ\n\n👤 " + tenant.getFullName()
                    + " (ID: " + targetTelegramId + ")\n🏠 Phòng: " + tenant.getRoom().getRoomNumber()
                    + "\n\nTài khoản vẫn được giữ lại.", TelegramKeyboardUtil.getConfirmKeyboard(
                            "TENANT_REMOVE_CONFIRM_" + tenant.getRoom().getId() + "_" + targetTelegramId, "/cancel"));
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void listAllTenants(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendNotice(chatId, "Bạn không có quyền thực hiện chức năng này.");
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
            sb.append("   📞 Số điện thoại: ").append(u.getPhoneNumberDisplay()).append("\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getLandlordTenantManagementMenu());
    }

    private void listRoomTenants(Long chatId, User user, Long roomId, BotMessageSender messageSender) {
        Room room = roomService.getRoomById(roomId);
        List<User> tenants = roomService.getRoomTenants(roomId);

        if (tenants == null || tenants.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "👥 NGƯỜI THUÊ PHÒNG " + room.getRoomNumber() + "\n\nPhòng hiện chưa có người ở.", TelegramKeyboardUtil.getBackKeyboard("ROOM_DETAIL_" + roomId));
            return;
        }

        StringBuilder sb = new StringBuilder("👥 NGƯỜI THUÊ PHÒNG " + room.getRoomNumber() + "\n\n");
        int index = 1;
        for (User u : tenants) {
            sb.append(index++).append(". ").append(u.getFullName() != null ? u.getFullName() : "Tenant").append("\n");
            sb.append("   📱 Telegram ID: ").append(u.getTelegramId()).append("\n");
            sb.append("   📞 Số điện thoại: ").append(u.getPhoneNumberDisplay()).append("\n");
            sb.append("   Username: ").append(u.getUsername() != null ? u.getUsername() : "Chưa đặt").append("\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getBackKeyboard("ROOM_DETAIL_" + roomId));
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
