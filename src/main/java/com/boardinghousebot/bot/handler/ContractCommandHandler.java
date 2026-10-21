package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.ContractService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class ContractCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(ContractCommandHandler.class);
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(new Locale("vi", "VN"));
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ContractService contractService;
    private final UserService userService;
    private final ConversationStateService stateService;

    public ContractCommandHandler(ContractService contractService,
                                  UserService userService,
                                  ConversationStateService stateService) {
        this.contractService = contractService;
        this.userService = userService;
        this.stateService = stateService;
    }

    @Override
    public String getCommand() {
        return "/contract";
    }

    @Override
    public String getDescription() {
        return "Quản lý hợp đồng thuê phòng";
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

        log.info("CONTRACT STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), text);

        if ("/cancel".equalsIgnoreCase(text) || "CANCEL_CREATE_CONTRACT".equals(text) || "CANCEL_UPDATE_CONTRACT".equals(text)) {
            boolean isUpdate = "CANCEL_UPDATE_CONTRACT".equals(text) || state.getStep().name().startsWith("UPDATE_CONTRACT");
            stateService.clearState(user.getTelegramId());
            String cancelMessage = isUpdate ? "❌ Đã hủy thao tác sửa hợp đồng." : "❌ Đã hủy thao tác tạo hợp đồng.";
            messageSender.sendMessageWithKeyboard(chatId, cancelMessage, TelegramKeyboardUtil.getLandlordContractManagementMenu());
            return;
        }

        // Process Callback Query actions (Inline buttons) first
        if (text.startsWith("CONTRACT_SELECT_ROOM_")) {
            handleRoomSelected(chatId, user, text, state, messageSender);
            return;
        }
        if (text.startsWith("CONTRACT_SELECT_MEMBER_")) {
            handleMemberSelected(chatId, user, text, state, messageSender);
            return;
        }

        if (text.startsWith("CONTRACT_USE_RENT_") || text.startsWith("CONTRACT_USE_DEPOSIT_")) {
            handleListedPrice(chatId, user, text, state, messageSender);
            return;
        }

        if (text.equals("CONFIRM_CREATE_CONTRACT")) {
            executeCreateContract(chatId, user, state, messageSender);
            return;
        }

        if (text.equals("CONFIRM_UPDATE_CONTRACT")) {
            executeUpdateContract(chatId, user, state, messageSender);
            return;
        }

        if (text.startsWith("CONFIRM_END_CONTRACT_")) {
            Long contractId = Long.parseLong(text.replace("CONFIRM_END_CONTRACT_", "").trim());
            executeEndContract(chatId, user, contractId, messageSender);
            return;
        }

        if (text.startsWith("CONTRACT_EDIT_")) {
            Long contractId = Long.parseLong(text.replace("CONTRACT_EDIT_", "").trim());
            startUpdateContractFlow(chatId, user, contractId, state, messageSender);
            return;
        }

        if (text.startsWith("CONTRACT_END_")) {
            Long contractId = Long.parseLong(text.replace("CONTRACT_END_", "").trim());
            promptEndContractConfirmation(chatId, user, contractId, messageSender);
            return;
        }

        // Process active conversation step for text inputs
        if (state.getStep() != UserConversationState.Step.NONE) {
            handleConversationStep(chatId, user, text, state, messageSender);
            return;
        }

        // Process non-state commands
        if (text.startsWith("CONTRACT_DETAIL_") || text.startsWith("/contract_detail_")) {
            String idStr = text.startsWith("CONTRACT_DETAIL_") ? text.replace("CONTRACT_DETAIL_", "") : text.replace("/contract_detail_", "");
            showContractDetail(chatId, user, Long.parseLong(idStr.trim()), messageSender);
            return;
        }

        switch (text) {
            case "LANDLORD_CONTRACTS", "CONTRACT_MENU", "/contract" -> showContractMenu(chatId, user, messageSender);
            case "CONTRACT_ADD" -> startCreateContractFlow(chatId, user, state, messageSender);
            case "CONTRACT_LIST_ALL" -> listContracts(chatId, user, messageSender, "ALL");
            case "CONTRACT_LIST_ACTIVE" -> listContracts(chatId, user, messageSender, "ACTIVE");
            case "CONTRACT_LIST_ENDED" -> listContracts(chatId, user, messageSender, "ENDED");
            case "CONTRACT_LIST_EXPIRING" -> listContracts(chatId, user, messageSender, "EXPIRING");
            case "MEMBER_CONTRACT" -> showMemberContract(chatId, user, messageSender);
            default -> showContractMenu(chatId, user, messageSender);
        }
    }

    private void showContractMenu(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() == Role.MEMBER) {
            showMemberContract(chatId, user, messageSender);
            return;
        }
        messageSender.sendMessageWithKeyboard(chatId, "📄 QUẢN LÝ HỢP ĐỒNG\n\nVui lòng chọn thao tác bên dưới:", TelegramKeyboardUtil.getLandlordContractManagementMenu());
    }

    private void startCreateContractFlow(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendNotice(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<Room> eligibleRooms = userService.getUsersByRoleWithRoom(Role.MEMBER).stream()
                .filter(m -> m.getRoom() != null)
                .map(User::getRoom)
                .collect(java.util.stream.Collectors.toMap(Room::getId, r -> r, (first, second) -> first,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .filter(r -> !contractService.hasActiveContractForRoom(r.getId()))
                .sorted(java.util.Comparator.comparing(Room::getRoomNumber))
                .toList();

        state = new UserConversationState(UserConversationState.Step.CREATE_CONTRACT_ROOM_SELECT);
        stateService.setState(user.getTelegramId(), state);
        if (eligibleRooms.isEmpty()) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendMessageWithKeyboard(chatId, "📄 TẠO HỢP ĐỒNG\n\nKhông có phòng có người thuê và chưa có hợp đồng đang hiệu lực.", TelegramKeyboardUtil.getLandlordContractManagementMenu());
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Room room : eligibleRooms) {
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🏠 Phòng " + room.getRoomNumber(),
                    "CONTRACT_SELECT_ROOM_" + room.getId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_CREATE_CONTRACT")));
        messageSender.sendMessageWithKeyboard(chatId, "🏠 CHỌN PHÒNG ĐỂ TẠO HỢP ĐỒNG:\n\nDanh sách phòng có người thuê và chưa có hợp đồng đang hiệu lực:", new InlineKeyboardMarkup(rows));
    }

    private void handleRoomSelected(Long chatId, User user, String text, UserConversationState state, BotMessageSender messageSender) {
        if (state.getStep() != UserConversationState.Step.CREATE_CONTRACT_ROOM_SELECT) {
            messageSender.sendNotice(chatId, "❌ Vui lòng bắt đầu tạo hợp đồng và chọn phòng trước.");
            return;
        }
        Long roomId = Long.parseLong(text.substring("CONTRACT_SELECT_ROOM_".length()));
        if (contractService.hasActiveContractForRoom(roomId)) {
            messageSender.sendNotice(chatId, "❌ Phòng đã có hợp đồng đang hiệu lực. Vui lòng chọn phòng khác.");
            startCreateContractFlow(chatId, user, state, messageSender);
            return;
        }
        List<User> eligibleMembers = userService.getUsersByRoleWithRoom(Role.MEMBER).stream()
                .filter(m -> m.getRoom() != null && roomId.equals(m.getRoom().getId()))
                .filter(m -> contractService.getMemberActiveContract(m.getTelegramId()).isEmpty())
                .toList();
        if (eligibleMembers.isEmpty()) {
            messageSender.sendNotice(chatId, "❌ Phòng không còn người thuê đủ điều kiện. Vui lòng chọn phòng khác.");
            startCreateContractFlow(chatId, user, state, messageSender);
            return;
        }
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (User m : eligibleMembers) {
            String label = "👤 " + (m.getFullName() != null ? m.getFullName() : m.getUsername()) + " - Phòng " + m.getRoom().getRoomNumber();
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "CONTRACT_SELECT_MEMBER_" + m.getTelegramId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_CREATE_CONTRACT")));

        state.setTargetRoomId(roomId);
        state.setRoomNumber(eligibleMembers.get(0).getRoom().getRoomNumber());
        state.setStep(UserConversationState.Step.CREATE_CONTRACT_MEMBER_SELECT);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendMessageWithKeyboard(chatId, "👤 CHỌN NGƯỜI ĐỨNG TÊN HỢP ĐỒNG PHÒNG " + state.getRoomNumber()
                + ":\n\nChọn một người thuê trong phòng làm đại diện đứng tên hợp đồng.", new InlineKeyboardMarkup(rows));
    }

    private void handleMemberSelected(Long chatId, User user, String text, UserConversationState state, BotMessageSender messageSender) {
        if (state.getStep() != UserConversationState.Step.CREATE_CONTRACT_MEMBER_SELECT || state.getTargetRoomId() == null) {
            messageSender.sendNotice(chatId, "❌ Vui lòng chọn phòng trước khi chọn người đứng tên hợp đồng.");
            return;
        }
        Long targetMemberId = Long.parseLong(text.replace("CONTRACT_SELECT_MEMBER_", "").trim());
        User member = userService.findByTelegramIdWithRoom(targetMemberId).orElse(null);
        if (member == null || member.getRole() != Role.MEMBER || member.getRoom() == null
                || !state.getTargetRoomId().equals(member.getRoom().getId())
                || contractService.hasActiveContractForRoom(state.getTargetRoomId())
                || contractService.getMemberActiveContract(targetMemberId).isPresent()) {
            messageSender.sendNotice(chatId, "❌ Thông tin phòng hoặc người thuê đã thay đổi. Vui lòng chọn lại phòng.");
            startCreateContractFlow(chatId, user, state, messageSender);
            return;
        }
        state.setTargetUserTelegramId(targetMemberId);
        state.setStep(UserConversationState.Step.CREATE_CONTRACT_START_DATE);
        stateService.setState(user.getTelegramId(), state);

        log.info("CONTRACT STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), text);

        String representativeName = member.getFullName() != null && !member.getFullName().isBlank()
                ? member.getFullName() : member.getUsername() != null && !member.getUsername().isBlank()
                ? member.getUsername() : String.valueOf(member.getTelegramId());
        messageSender.sendPrompt(chatId, "📄 TẠO HỢP ĐỒNG\n👤 Người đại diện: " + representativeName
                + "\n\nNhập ngày bắt đầu hợp đồng (định dạng dd/MM/yyyy, ví dụ: 20/09/2026):");
    }

    private void handleConversationStep(Long chatId, User user, String input, UserConversationState state, BotMessageSender messageSender) {
        log.info("CONTRACT STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), input);
        switch (state.getStep()) {
            case CREATE_CONTRACT_ROOM_SELECT -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng chọn phòng từ danh sách phím bấm bên dưới.");
            }
            case CREATE_CONTRACT_MEMBER_SELECT -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng chọn người thuê từ danh sách phím bấm bên dưới.");
            }
            case CREATE_CONTRACT_START_DATE -> {
                LocalDate startDate = parseDate(input);
                if (startDate == null) {
                    messageSender.sendNotice(chatId, "❌ Ngày không hợp lệ. Vui lòng nhập theo định dạng dd/MM/yyyy (ví dụ: 20/09/2026):");
                    return;
                }
                state.getData().put("startDate", startDate);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_END_DATE);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: startDate={}, next step=CREATE_CONTRACT_END_DATE", startDate);
                messageSender.sendPrompt(chatId, "📅 Nhập ngày kết thúc hợp đồng (định dạng dd/MM/yyyy, ví dụ: 20/09/2027):");
            }
            case CREATE_CONTRACT_END_DATE -> {
                LocalDate endDate = parseDate(input);
                LocalDate startDate = (LocalDate) state.getData().get("startDate");
                if (endDate == null) {
                    messageSender.sendNotice(chatId, "❌ Ngày không hợp lệ. Vui lòng nhập theo định dạng dd/MM/yyyy (ví dụ: 20/09/2027):");
                    return;
                }
                if (!endDate.isAfter(startDate)) {
                    messageSender.sendNotice(chatId, "❌ Ngày kết thúc phải sau ngày bắt đầu (" + startDate.format(DATE_FORMATTER) + "). Vui lòng nhập lại ngày kết thúc:");
                    return;
                }
                state.getData().put("endDate", endDate);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_RENT);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: endDate={}, next step=CREATE_CONTRACT_RENT", endDate);
                showPricePrompt(chatId, state, true, messageSender);
            }
            case CREATE_CONTRACT_RENT -> {
                BigDecimal rent = parseBigDecimal(input);
                if (rent == null || rent.compareTo(BigDecimal.ZERO) <= 0) {
                    messageSender.sendNotice(chatId, "❌ Giá thuê phải là số lớn hơn 0. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("rent", rent);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_DEPOSIT);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: rent={}, next step=CREATE_CONTRACT_DEPOSIT", rent);
                showPricePrompt(chatId, state, false, messageSender);
            }
            case CREATE_CONTRACT_DEPOSIT -> {
                BigDecimal deposit = parseBigDecimal(input);
                if (deposit == null || deposit.compareTo(BigDecimal.ZERO) < 0) {
                    messageSender.sendNotice(chatId, "❌ Tiền cọc phải là số không âm (>= 0). Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("deposit", deposit);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_CONFIRM);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: deposit={}, next step=CREATE_CONTRACT_CONFIRM", deposit);

                showContractCreatePreview(chatId, state, messageSender);
            }
            case CREATE_CONTRACT_CONFIRM -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            case UPDATE_CONTRACT_START_DATE -> {
                LocalDate startDate = parseDate(input);
                if (startDate == null) {
                    messageSender.sendNotice(chatId, "❌ Ngày không hợp lệ. Vui lòng nhập theo định dạng dd/MM/yyyy:");
                    return;
                }
                state.getData().put("startDate", startDate);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_END_DATE);
                stateService.setState(user.getTelegramId(), state);
                messageSender.sendPrompt(chatId, "📅 Nhập ngày kết thúc mới (dd/MM/yyyy):");
            }
            case UPDATE_CONTRACT_END_DATE -> {
                LocalDate endDate = parseDate(input);
                LocalDate startDate = (LocalDate) state.getData().get("startDate");
                if (endDate == null || !endDate.isAfter(startDate)) {
                    messageSender.sendNotice(chatId, "❌ Ngày kết thúc không hợp lệ hoặc không sau ngày bắt đầu. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("endDate", endDate);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_RENT);
                stateService.setState(user.getTelegramId(), state);
                showPricePrompt(chatId, state, true, messageSender);
            }
            case UPDATE_CONTRACT_RENT -> {
                BigDecimal rent = parseBigDecimal(input);
                if (rent == null || rent.compareTo(BigDecimal.ZERO) <= 0) {
                    messageSender.sendNotice(chatId, "❌ Giá thuê phải lớn hơn 0. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("rent", rent);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_DEPOSIT);
                stateService.setState(user.getTelegramId(), state);
                showPricePrompt(chatId, state, false, messageSender);
            }
            case UPDATE_CONTRACT_DEPOSIT -> {
                BigDecimal deposit = parseBigDecimal(input);
                if (deposit == null || deposit.compareTo(BigDecimal.ZERO) < 0) {
                    messageSender.sendNotice(chatId, "❌ Tiền cọc không được âm. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("deposit", deposit);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_CONFIRM);
                stateService.setState(user.getTelegramId(), state);

                showContractUpdatePreview(chatId, state, messageSender);
            }
            case UPDATE_CONTRACT_CONFIRM -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            default -> {
                stateService.clearState(user.getTelegramId());
                showContractMenu(chatId, user, messageSender);
            }
        }
    }

    private Room selectedPriceRoom(UserConversationState state) {
        if (state.getStep().name().startsWith("UPDATE_CONTRACT")) {
            Long contractId = (Long) state.getData().get("contractId");
            return contractService.getContractById(contractId).getRoom();
        }
        return userService.findByTelegramIdWithRoom(state.getTargetUserTelegramId())
                .map(User::getRoom)
                .filter(room -> state.getTargetRoomId() == null || state.getTargetRoomId().equals(room.getId()))
                .orElse(null);
    }

    private void showPricePrompt(Long chatId, UserConversationState state, boolean rent, BotMessageSender sender) {
        Room room = selectedPriceRoom(state);
        BigDecimal amount = room == null ? null : rent ? room.getMonthlyRent() : room.getRequiredDeposit();
        String key = rent ? "listedRent" : "listedDeposit";
        state.getData().remove(key);
        var rows = new ArrayList<InlineKeyboardRow>();
        String text = rent ? "💰 TIỀN THUÊ HẰNG THÁNG" : "💵 TIỀN CỌC";
        if (room != null) text += "\nPhòng " + room.getRoomNumber();
        if (amount != null) {
            state.getData().put(key, amount);
            text += (rent ? " · Giá niêm yết: " : " · Tiền cọc yêu cầu: ")
                    + CURRENCY_FORMAT.format(amount) + (rent ? "/tháng" : "");
            String label = rent ? "Dùng giá niêm yết · " : amount.signum() == 0 ? "Không thu cọc · " : "Dùng mức cọc của phòng · ";
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label + CURRENCY_FORMAT.format(amount),
                    (rent ? "CONTRACT_USE_RENT_" : "CONTRACT_USE_DEPOSIT_") + room.getId())));
            text += rent ? "\n\nNhập giá phòng theo tháng hoặc dùng giá niêm yết."
                    : "\n\nNhập tiền cọc hoặc dùng mức cọc của phòng.";
        } else {
            text += rent ? "\n\nChưa có giá niêm yết. Nhập tiền thuê đã thỏa thuận (VNĐ):"
                    : "\n\nPhòng chưa thiết lập tiền cọc. Nhập tiền cọc đã thỏa thuận (VNĐ):";
        }
        if (!rent) text += "\nNhập 0 nếu không thu cọc.";
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy",
                state.getStep().name().startsWith("UPDATE_CONTRACT") ? "CANCEL_UPDATE_CONTRACT" : "CANCEL_CREATE_CONTRACT")));
        sender.sendPrompt(chatId, text, new InlineKeyboardMarkup(rows));
    }

    private void handleListedPrice(Long chatId, User user, String action, UserConversationState state, BotMessageSender sender) {
        boolean rent = action.startsWith("CONTRACT_USE_RENT_");
        var expected = rent ? UserConversationState.Step.CREATE_CONTRACT_RENT : UserConversationState.Step.CREATE_CONTRACT_DEPOSIT;
        var updateExpected = rent ? UserConversationState.Step.UPDATE_CONTRACT_RENT : UserConversationState.Step.UPDATE_CONTRACT_DEPOSIT;
        if (state.getStep() != expected && state.getStep() != updateExpected) {
            sender.sendNotice(chatId, "Lựa chọn này đã hết hiệu lực. Vui lòng tiếp tục ở bước hiện tại.");
            return;
        }
        Room room = selectedPriceRoom(state);
        String prefix = rent ? "CONTRACT_USE_RENT_" : "CONTRACT_USE_DEPOSIT_";
        if (room == null || !action.equals(prefix + room.getId())) {
            sender.sendNotice(chatId, "Thông tin phòng đã thay đổi. Vui lòng hủy và mở lại thao tác hợp đồng.");
            return;
        }
        BigDecimal price = rent ? room.getMonthlyRent() : room.getRequiredDeposit();
        BigDecimal shown = (BigDecimal) state.getData().get(rent ? "listedRent" : "listedDeposit");
        if (price == null || shown == null || price.compareTo(shown) != 0) {
            sender.sendNotice(chatId, "Mức tiền của phòng đã thay đổi. Vui lòng kiểm tra và chọn lại.");
            showPricePrompt(chatId, state, rent, sender);
            return;
        }
        handleConversationStep(chatId, user, price.toPlainString(), state, sender);
    }

    private void showContractCreatePreview(Long chatId, UserConversationState state, BotMessageSender messageSender) {
        Long memberTelegramId = state.getTargetUserTelegramId();
        User member = userService.findByTelegramIdWithRoom(memberTelegramId).orElseThrow();
        LocalDate startDate = (LocalDate) state.getData().get("startDate");
        LocalDate endDate = (LocalDate) state.getData().get("endDate");
        BigDecimal rent = (BigDecimal) state.getData().get("rent");
        BigDecimal deposit = (BigDecimal) state.getData().get("deposit");

        String preview = String.format("""
                📄 XÁC NHẬN TẠO HỢP ĐỒNG

                👤 Người đại diện: %s
                📱 Telegram ID: %d
                🏠 Phòng: %s
                📅 Ngày bắt đầu: %s
                📅 Ngày kết thúc: %s
                💰 Tiền thuê: %s
                💵 Tiền cọc: %s
                """,
                member.getFullName() != null ? member.getFullName() : member.getUsername(),
                member.getTelegramId(),
                member.getRoom() != null ? member.getRoom().getRoomNumber() : "N/A",
                startDate.format(DATE_FORMATTER),
                endDate.format(DATE_FORMATTER),
                CURRENCY_FORMAT.format(rent),
                CURRENCY_FORMAT.format(deposit)
        );

        Room room = member.getRoom();
        if (room != null) {
            if (room.getMonthlyRent() != null && rent.compareTo(room.getMonthlyRent()) != 0)
                preview += "\nGiá thuê niêm yết: " + CURRENCY_FORMAT.format(room.getMonthlyRent()) + "/tháng";
            if (room.getRequiredDeposit() != null && deposit.compareTo(room.getRequiredDeposit()) != 0)
                preview += "\nTiền cọc yêu cầu của phòng: " + CURRENCY_FORMAT.format(room.getRequiredDeposit());
        }
        preview += "\n📅 Thanh toán hóa đơn: ngày " + startDate.getDayOfMonth() + " hằng tháng (tháng không có ngày tương ứng dùng ngày cuối tháng).";
        preview += "\n\nCác mức tiền trên chỉ áp dụng cho hợp đồng này, không thay đổi giá niêm yết của phòng.";
        messageSender.sendMessageWithKeyboard(chatId, preview, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_CREATE_CONTRACT", "CANCEL_CREATE_CONTRACT"));
    }

    private void showContractUpdatePreview(Long chatId, UserConversationState state, BotMessageSender messageSender) {
        Long contractId = (Long) state.getData().get("contractId");
        LocalDate startDate = (LocalDate) state.getData().get("startDate");
        LocalDate endDate = (LocalDate) state.getData().get("endDate");
        BigDecimal rent = (BigDecimal) state.getData().get("rent");
        BigDecimal deposit = (BigDecimal) state.getData().get("deposit");

        String preview = String.format("""
                ✏️ XÁC NHẬN CẬP NHẬT HỢP ĐỒNG #%d

                📅 Ngày bắt đầu mới: %s
                📅 Ngày kết thúc mới: %s
                💰 Tiền thuê mới: %s
                💵 Tiền cọc mới: %s
                """,
                contractId,
                startDate.format(DATE_FORMATTER),
                endDate.format(DATE_FORMATTER),
                CURRENCY_FORMAT.format(rent),
                CURRENCY_FORMAT.format(deposit)
        );

        messageSender.sendMessageWithKeyboard(chatId, preview, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_UPDATE_CONTRACT", "CANCEL_UPDATE_CONTRACT"));
    }

    private void executeCreateContract(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        if (state.getStep() != UserConversationState.Step.CREATE_CONTRACT_CONFIRM || state.getTargetRoomId() == null) {
            messageSender.sendNotice(chatId, "❌ Vui lòng tạo hợp đồng theo các bước chọn phòng và người đứng tên trước khi xác nhận.");
            return;
        }
        try {
            Long memberTelegramId = state.getTargetUserTelegramId();
            LocalDate startDate = (LocalDate) state.getData().get("startDate");
            LocalDate endDate = (LocalDate) state.getData().get("endDate");
            BigDecimal rent = (BigDecimal) state.getData().get("rent");
            BigDecimal deposit = (BigDecimal) state.getData().get("deposit");

            Contract contract = contractService.createContract(user.getTelegramId(), memberTelegramId, startDate, endDate, rent, deposit, state.getTargetRoomId());

            stateService.clearState(user.getTelegramId());
            messageSender.sendResultAndMenu(chatId, "✅ Đã tạo hợp đồng thuê thành công cho phòng " + contract.getRoom().getRoomNumber() + "!", TelegramKeyboardUtil.getLandlordContractManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void startUpdateContractFlow(Long chatId, User user, Long contractId, UserConversationState state, BotMessageSender messageSender) {
        Contract contract = contractService.getContractById(contractId);
        if (contract.getStatus() == ContractStatus.ENDED) {
            messageSender.sendNotice(chatId, "❌ Không thể chỉnh sửa hợp đồng đã kết thúc.");
            return;
        }

        state.getData().put("contractId", contractId);
        state.setStep(UserConversationState.Step.UPDATE_CONTRACT_START_DATE);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendPrompt(chatId, "✏️ SỬA HỢP ĐỒNG #" + contractId + "\n\nNhập ngày bắt đầu mới (dd/MM/yyyy):");
    }

    private void executeUpdateContract(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long contractId = (Long) state.getData().get("contractId");
            LocalDate startDate = (LocalDate) state.getData().get("startDate");
            LocalDate endDate = (LocalDate) state.getData().get("endDate");
            BigDecimal rent = (BigDecimal) state.getData().get("rent");
            BigDecimal deposit = (BigDecimal) state.getData().get("deposit");

            Contract updated = contractService.updateContract(user.getTelegramId(), contractId, startDate, endDate, rent, deposit);

            stateService.clearState(user.getTelegramId());
            messageSender.sendResultAndMenu(chatId, "✅ Đã cập nhật hợp đồng #" + updated.getId() + " thành công!", TelegramKeyboardUtil.getLandlordContractManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void promptEndContractConfirmation(Long chatId, User user, Long contractId, BotMessageSender messageSender) {
        Contract contract = contractService.getContractById(contractId);
        String text = String.format("""
                ⚠️ XÁC NHẬN KẾT THÚC HỢP ĐỒNG?

                👤 Người đại diện: %s
                🏠 Phòng: %s
                📅 Hạn hợp đồng: %s - %s

                Xác nhận sẽ kết thúc hợp đồng và gỡ toàn bộ người thuê khỏi phòng.
                Phòng sẽ chuyển về trạng thái trống. Lịch sử hợp đồng và hóa đơn được giữ nguyên.
                """,
                contract.getMember().getFullName() != null ? contract.getMember().getFullName() : contract.getMember().getUsername(),
                contract.getRoom().getRoomNumber(),
                contract.getStartDate().format(DATE_FORMATTER),
                contract.getEndDate().format(DATE_FORMATTER)
        );

        messageSender.sendMessageWithKeyboard(chatId, text, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_END_CONTRACT_" + contractId, "CONTRACT_DETAIL_" + contractId));
    }

    private void executeEndContract(Long chatId, User user, Long contractId, BotMessageSender messageSender) {
        try {
            Contract contract = contractService.endContract(user.getTelegramId(), contractId);
            messageSender.sendResultAndMenu(chatId, "✅ Đã kết thúc hợp đồng #" + contract.getId()
                    + " và gỡ toàn bộ người thuê khỏi phòng " + contract.getRoom().getRoomNumber() + ".",
                    TelegramKeyboardUtil.getLandlordContractManagementMenu());
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void listContracts(Long chatId, User user, BotMessageSender messageSender, String filter) {
        List<Contract> contracts = switch (filter) {
            case "ACTIVE" -> contractService.getActiveContracts();
            case "ENDED" -> contractService.getEndedContracts();
            case "EXPIRING" -> contractService.getExpiringContracts(30);
            default -> contractService.getAllContracts();
        };

        String title = switch (filter) {
            case "ACTIVE" -> "🟢 HỢP ĐỒNG ĐANG HIỆU LỰC";
            case "ENDED" -> "⚫ HỢP ĐỒNG ĐÃ KẾT THÚC";
            case "EXPIRING" -> "⏰ HỢP ĐỒNG SẮP HẾT HẠN (30 ngày)";
            default -> "📄 DANH SÁCH HỢP ĐỒNG";
        };

        if (contracts.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, title + "\n\nKhông có hợp đồng nào phù hợp.", TelegramKeyboardUtil.getContractListKeyboard(contracts));
            return;
        }

        StringBuilder sb = new StringBuilder(title + " (" + contracts.size() + " hợp đồng)\n\n");
        for (Contract c : contracts) {
            String statusBadge = c.getStatus() == ContractStatus.ACTIVE ? "🟢 Đang hiệu lực" : "⚫ Đã kết thúc";
            sb.append("📄 HĐ #").append(c.getId()).append(" · 🏠 Phòng ").append(c.getRoom().getRoomNumber()).append("\n");
            sb.append("   👤 ").append(c.getMember().getFullName() != null ? c.getMember().getFullName() : c.getMember().getUsername()).append("\n");
            sb.append("   📅 ").append(c.getStartDate().format(DATE_FORMATTER)).append(" → ").append(c.getEndDate().format(DATE_FORMATTER)).append("\n");
            sb.append("   💰 ").append(CURRENCY_FORMAT.format(c.getMonthlyRent())).append("/tháng · ").append(statusBadge).append("\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getContractListKeyboard(contracts));
    }

    private void showContractDetail(Long chatId, User user, Long contractId, BotMessageSender messageSender) {
        Contract c = contractService.getContractForUser(user, contractId);
        if (user.getRole() == Role.MEMBER) {
            showMemberContractDetails(chatId, c, messageSender);
            return;
        }

        String statusStr = c.getStatus() == ContractStatus.ACTIVE ? "🟢 Đang hiệu lực" : "⚫ Đã kết thúc";
        String detailText = String.format("""
                📄 CHI TIẾT HỢP ĐỒNG #%d

                👤 Người đại diện: %s
                🆔 Telegram ID: %d
                🏠 Phòng: %s
                📅 Ngày bắt đầu: %s
                📅 Ngày kết thúc: %s
                💰 Tiền thuê: %s/tháng
                💵 Tiền cọc: %s
                📌 Trạng thái: %s
                """,
                c.getId(),
                c.getMember().getFullName() != null ? c.getMember().getFullName() : c.getMember().getUsername(),
                c.getMember().getTelegramId(),
                c.getRoom().getRoomNumber(),
                c.getStartDate().format(DATE_FORMATTER),
                c.getEndDate().format(DATE_FORMATTER),
                CURRENCY_FORMAT.format(c.getMonthlyRent()),
                CURRENCY_FORMAT.format(c.getDeposit()),
                statusStr
        );

        detailText += "\n📅 Thanh toán hóa đơn: ngày " + c.getStartDate().getDayOfMonth() + " hằng tháng.\nNếu tháng không có ngày tương ứng, thanh toán vào ngày cuối tháng.";
        if (user.getRole() == Role.LANDLORD) {
            messageSender.sendMessageWithKeyboard(chatId, detailText, TelegramKeyboardUtil.getContractDetailKeyboard(c.getId(), c.getStatus() == ContractStatus.ENDED));
        } else {
            messageSender.sendMessageWithKeyboard(chatId, detailText, TelegramKeyboardUtil.getMemberMenu());
        }
    }

    private void showMemberContract(Long chatId, User user, BotMessageSender messageSender) {
        Optional<Contract> contractOpt = contractService.getCurrentRoomActiveContract(user.getTelegramId());

        if (contractOpt.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "📄 HỢP ĐỒNG PHÒNG\n\nPhòng của bạn hiện chưa có hợp đồng đang hiệu lực.", TelegramKeyboardUtil.getMemberMenu());
            return;
        }

        showMemberContractDetails(chatId, contractOpt.get(), messageSender);
    }

    private void showMemberContractDetails(Long chatId, Contract c, BotMessageSender messageSender) {
        String memberText = String.format("""
                📄 HỢP ĐỒNG PHÒNG %s

                👤 Người đại diện: %s
                🏠 Phòng: %s
                📅 Ngày bắt đầu: %s
                📅 Ngày kết thúc: %s
                💰 Tiền thuê: %s/tháng
                💵 Tiền cọc: %s
                📌 Trạng thái: %s
                """,
                c.getRoom().getRoomNumber(),
                c.getMember().getFullName() != null ? c.getMember().getFullName() : c.getMember().getUsername(),
                c.getRoom().getRoomNumber(),
                c.getStartDate().format(DATE_FORMATTER),
                c.getEndDate().format(DATE_FORMATTER),
                CURRENCY_FORMAT.format(c.getMonthlyRent()),
                CURRENCY_FORMAT.format(c.getDeposit()),
                c.getStatus() == ContractStatus.ACTIVE ? "🟢 Đang hiệu lực" : "⚫ Đã kết thúc"
        );

        memberText += "\n📅 Thanh toán hóa đơn: ngày " + c.getStartDate().getDayOfMonth() + " hằng tháng.\nNếu tháng không có ngày tương ứng, thanh toán vào ngày cuối tháng.";
        messageSender.sendMessageWithKeyboard(chatId, memberText, TelegramKeyboardUtil.getMemberMenu());
    }

    private LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text.trim(), DATE_FORMATTER);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDate.parse(text.trim());
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private BigDecimal parseBigDecimal(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            String clean = text.trim().replaceAll("[^0-9.-]", "");
            return new BigDecimal(clean);
        } catch (Exception e) {
            return null;
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
