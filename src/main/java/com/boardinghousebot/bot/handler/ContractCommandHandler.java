package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
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
        if (text.startsWith("CONTRACT_SELECT_MEMBER_")) {
            handleMemberSelected(chatId, user, text, state, messageSender);
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
            messageSender.sendMessage(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<User> members = userService.getUsersByRoleWithRoom(Role.MEMBER);
        List<User> eligibleMembers = members.stream()
                .filter(m -> m.getRoom() != null)
                .filter(m -> {
                    try {
                        return contractService.getMemberActiveContract(m.getTelegramId()).isEmpty();
                    } catch (Exception e) {
                        return true;
                    }
                })
                .toList();

        if (eligibleMembers.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "📄 TẠO HỢP ĐỒNG\n\nKhông có người thuê nào đủ điều kiện để tạo hợp đồng.", TelegramKeyboardUtil.getLandlordContractManagementMenu());
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (User m : eligibleMembers) {
            String label = "👤 " + (m.getFullName() != null ? m.getFullName() : m.getUsername()) + " - Phòng " + m.getRoom().getRoomNumber();
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "CONTRACT_SELECT_MEMBER_" + m.getTelegramId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_CREATE_CONTRACT")));

        state.setStep(UserConversationState.Step.CREATE_CONTRACT_MEMBER_SELECT);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendMessageWithKeyboard(chatId, "👤 CHỌN NGƯỜI THUÊ ĐỂ TẠO HỢP ĐỒNG:\n\nDanh sách người thuê chưa có hợp đồng đang hiệu lực:", new InlineKeyboardMarkup(rows));
    }

    private void handleMemberSelected(Long chatId, User user, String text, UserConversationState state, BotMessageSender messageSender) {
        Long targetMemberId = Long.parseLong(text.replace("CONTRACT_SELECT_MEMBER_", "").trim());
        state.setTargetUserTelegramId(targetMemberId);
        state.setStep(UserConversationState.Step.CREATE_CONTRACT_START_DATE);
        stateService.setState(user.getTelegramId(), state);

        log.info("CONTRACT STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), text);

        messageSender.sendMessage(chatId, "📄 TẠO HỢP ĐỒNG\n\nNhập ngày bắt đầu hợp đồng (định dạng dd/MM/yyyy, ví dụ: 20/09/2026):\n\nGửi /cancel để hủy.");
    }

    private void handleConversationStep(Long chatId, User user, String input, UserConversationState state, BotMessageSender messageSender) {
        log.info("CONTRACT STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), input);
        switch (state.getStep()) {
            case CREATE_CONTRACT_MEMBER_SELECT -> {
                messageSender.sendMessage(chatId, "❌ Vui lòng chọn người thuê từ danh sách phím bấm bên dưới hoặc gửi /cancel để hủy.");
            }
            case CREATE_CONTRACT_START_DATE -> {
                LocalDate startDate = parseDate(input);
                if (startDate == null) {
                    messageSender.sendMessage(chatId, "❌ Ngày không hợp lệ. Vui lòng nhập theo định dạng dd/MM/yyyy (ví dụ: 20/09/2026):");
                    return;
                }
                state.getData().put("startDate", startDate);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_END_DATE);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: startDate={}, next step=CREATE_CONTRACT_END_DATE", startDate);
                messageSender.sendMessage(chatId, "📅 Nhập ngày kết thúc hợp đồng (định dạng dd/MM/yyyy, ví dụ: 20/09/2027):\n\nGửi /cancel để hủy.");
            }
            case CREATE_CONTRACT_END_DATE -> {
                LocalDate endDate = parseDate(input);
                LocalDate startDate = (LocalDate) state.getData().get("startDate");
                if (endDate == null) {
                    messageSender.sendMessage(chatId, "❌ Ngày không hợp lệ. Vui lòng nhập theo định dạng dd/MM/yyyy (ví dụ: 20/09/2027):");
                    return;
                }
                if (!endDate.isAfter(startDate)) {
                    messageSender.sendMessage(chatId, "❌ Ngày kết thúc phải sau ngày bắt đầu (" + startDate.format(DATE_FORMATTER) + "). Vui lòng nhập lại ngày kết thúc:");
                    return;
                }
                state.getData().put("endDate", endDate);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_RENT);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: endDate={}, next step=CREATE_CONTRACT_RENT", endDate);
                messageSender.sendMessage(chatId, "💰 Nhập tiền thuê tháng (VNĐ, ví dụ: 1500000):\n\nGửi /cancel để hủy.");
            }
            case CREATE_CONTRACT_RENT -> {
                BigDecimal rent = parseBigDecimal(input);
                if (rent == null || rent.compareTo(BigDecimal.ZERO) <= 0) {
                    messageSender.sendMessage(chatId, "❌ Giá thuê phải là số lớn hơn 0. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("rent", rent);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_DEPOSIT);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: rent={}, next step=CREATE_CONTRACT_DEPOSIT", rent);
                messageSender.sendMessage(chatId, "💵 Nhập tiền đặt cọc (VNĐ, ví dụ: 3000000):\n\nGửi /cancel để hủy.");
            }
            case CREATE_CONTRACT_DEPOSIT -> {
                BigDecimal deposit = parseBigDecimal(input);
                if (deposit == null || deposit.compareTo(BigDecimal.ZERO) < 0) {
                    messageSender.sendMessage(chatId, "❌ Tiền cọc phải là số không âm (>= 0). Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("deposit", deposit);
                state.setStep(UserConversationState.Step.CREATE_CONTRACT_CONFIRM);
                stateService.setState(user.getTelegramId(), state);
                log.info("Contract creation step: deposit={}, next step=CREATE_CONTRACT_CONFIRM", deposit);

                showContractCreatePreview(chatId, state, messageSender);
            }
            case CREATE_CONTRACT_CONFIRM -> {
                messageSender.sendMessage(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            case UPDATE_CONTRACT_START_DATE -> {
                LocalDate startDate = parseDate(input);
                if (startDate == null) {
                    messageSender.sendMessage(chatId, "❌ Ngày không hợp lệ. Vui lòng nhập theo định dạng dd/MM/yyyy:");
                    return;
                }
                state.getData().put("startDate", startDate);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_END_DATE);
                stateService.setState(user.getTelegramId(), state);
                messageSender.sendMessage(chatId, "📅 Nhập ngày kết thúc mới (dd/MM/yyyy):\n\nGửi /cancel để hủy.");
            }
            case UPDATE_CONTRACT_END_DATE -> {
                LocalDate endDate = parseDate(input);
                LocalDate startDate = (LocalDate) state.getData().get("startDate");
                if (endDate == null || !endDate.isAfter(startDate)) {
                    messageSender.sendMessage(chatId, "❌ Ngày kết thúc không hợp lệ hoặc không sau ngày bắt đầu. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("endDate", endDate);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_RENT);
                stateService.setState(user.getTelegramId(), state);
                messageSender.sendMessage(chatId, "💰 Nhập tiền thuê tháng mới (VNĐ):\n\nGửi /cancel để hủy.");
            }
            case UPDATE_CONTRACT_RENT -> {
                BigDecimal rent = parseBigDecimal(input);
                if (rent == null || rent.compareTo(BigDecimal.ZERO) <= 0) {
                    messageSender.sendMessage(chatId, "❌ Giá thuê phải lớn hơn 0. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("rent", rent);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_DEPOSIT);
                stateService.setState(user.getTelegramId(), state);
                messageSender.sendMessage(chatId, "💵 Nhập tiền cọc mới (VNĐ):\n\nGửi /cancel để hủy.");
            }
            case UPDATE_CONTRACT_DEPOSIT -> {
                BigDecimal deposit = parseBigDecimal(input);
                if (deposit == null || deposit.compareTo(BigDecimal.ZERO) < 0) {
                    messageSender.sendMessage(chatId, "❌ Tiền cọc không được âm. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("deposit", deposit);
                state.setStep(UserConversationState.Step.UPDATE_CONTRACT_CONFIRM);
                stateService.setState(user.getTelegramId(), state);

                showContractUpdatePreview(chatId, state, messageSender);
            }
            case UPDATE_CONTRACT_CONFIRM -> {
                messageSender.sendMessage(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            default -> {
                stateService.clearState(user.getTelegramId());
                showContractMenu(chatId, user, messageSender);
            }
        }
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
                
                👤 Người thuê: %s
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
        try {
            Long memberTelegramId = state.getTargetUserTelegramId();
            LocalDate startDate = (LocalDate) state.getData().get("startDate");
            LocalDate endDate = (LocalDate) state.getData().get("endDate");
            BigDecimal rent = (BigDecimal) state.getData().get("rent");
            BigDecimal deposit = (BigDecimal) state.getData().get("deposit");

            Contract contract = contractService.createContract(user.getTelegramId(), memberTelegramId, startDate, endDate, rent, deposit);

            stateService.clearState(user.getTelegramId());
            messageSender.sendMessageWithKeyboard(chatId, "✅ Đã tạo hợp đồng thuê thành công cho phòng " + contract.getRoom().getRoomNumber() + "!", TelegramKeyboardUtil.getLandlordContractManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordContractManagementMenu());
        }
    }

    private void startUpdateContractFlow(Long chatId, User user, Long contractId, UserConversationState state, BotMessageSender messageSender) {
        Contract contract = contractService.getContractById(contractId);
        if (contract.getStatus() == ContractStatus.ENDED) {
            messageSender.sendMessage(chatId, "❌ Không thể chỉnh sửa hợp đồng đã kết thúc.");
            return;
        }

        state.getData().put("contractId", contractId);
        state.setStep(UserConversationState.Step.UPDATE_CONTRACT_START_DATE);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendMessage(chatId, "✏️ SỬA HỢP ĐỒNG #" + contractId + "\n\nNhập ngày bắt đầu mới (dd/MM/yyyy):\n\nGửi /cancel để hủy.");
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
            messageSender.sendMessageWithKeyboard(chatId, "✅ Đã cập nhật hợp đồng #" + updated.getId() + " thành công!", TelegramKeyboardUtil.getLandlordContractManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendMessageWithKeyboard(chatId, "❌ " + e.getMessage(), TelegramKeyboardUtil.getLandlordContractManagementMenu());
        }
    }

    private void promptEndContractConfirmation(Long chatId, User user, Long contractId, BotMessageSender messageSender) {
        Contract contract = contractService.getContractById(contractId);
        String text = String.format("""
                ⚠️ XÁC NHẬN KẾT THÚC HỢP ĐỒNG?
                
                👤 Người thuê: %s
                🏠 Phòng: %s
                📅 Hạn hợp đồng: %s - %s
                
                Hành động này sẽ chuyển trạng thái hợp đồng sang ENDED.
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
            messageSender.sendMessageWithKeyboard(chatId, "✅ Đã kết thúc hợp đồng #" + contract.getId() + " thành công.", TelegramKeyboardUtil.getLandlordContractManagementMenu());
        } catch (BusinessException e) {
            messageSender.sendMessage(chatId, "❌ " + e.getMessage());
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
            messageSender.sendMessageWithKeyboard(chatId, title + "\n\nKhông có hợp đồng nào.", TelegramKeyboardUtil.getLandlordContractManagementMenu());
            return;
        }

        StringBuilder sb = new StringBuilder(title + " (" + contracts.size() + " hợp đồng)\n\n");
        int idx = 1;
        for (Contract c : contracts) {
            String statusBadge = c.getStatus() == ContractStatus.ACTIVE ? "🟢 ACTIVE" : "⚫ ENDED";
            sb.append(idx++).append(". ").append(c.getMember().getFullName() != null ? c.getMember().getFullName() : c.getMember().getUsername()).append("\n");
            sb.append("   🏠 Phòng: ").append(c.getRoom().getRoomNumber()).append("\n");
            sb.append("   📅 ").append(c.getStartDate().format(DATE_FORMATTER)).append(" → ").append(c.getEndDate().format(DATE_FORMATTER)).append("\n");
            sb.append("   💰 ").append(CURRENCY_FORMAT.format(c.getMonthlyRent())).append("\n");
            sb.append("   📌 ").append(statusBadge).append("\n");
            sb.append("   Chi tiết: `/contract_detail_").append(c.getId()).append("`\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getLandlordContractManagementMenu());
    }

    private void showContractDetail(Long chatId, User user, Long contractId, BotMessageSender messageSender) {
        Contract c = contractService.getContractById(contractId);

        String statusStr = c.getStatus() == ContractStatus.ACTIVE ? "🟢 Đang hiệu lực" : "⚫ Đã kết thúc";
        String detailText = String.format("""
                📄 CHI TIẾT HỢP ĐỒNG #%d
                
                👤 Người thuê: %s
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

        if (user.getRole() == Role.LANDLORD || user.getRole() == Role.ADMIN) {
            messageSender.sendMessageWithKeyboard(chatId, detailText, TelegramKeyboardUtil.getContractDetailKeyboard(c.getId(), c.getStatus() == ContractStatus.ENDED));
        } else {
            messageSender.sendMessage(chatId, detailText);
        }
    }

    private void showMemberContract(Long chatId, User user, BotMessageSender messageSender) {
        Optional<Contract> contractOpt = contractService.getMemberActiveContract(user.getTelegramId());

        if (contractOpt.isEmpty()) {
            messageSender.sendMessage(chatId, "📄 HỢP ĐỒNG CỦA TÔI\n\nBạn hiện chưa có hợp đồng đang hiệu lực.");
            return;
        }

        Contract c = contractOpt.get();
        String memberText = String.format("""
                📄 HỢP ĐỒNG CỦA TÔI (PHÒNG %s)
                
                🏠 Phòng: %s
                📅 Ngày bắt đầu: %s
                📅 Ngày kết thúc: %s
                💰 Tiền thuê: %s/tháng
                💵 Tiền cọc: %s
                📌 Trạng thái: 🟢 Đang hiệu lực
                """,
                c.getRoom().getRoomNumber(),
                c.getRoom().getRoomNumber(),
                c.getStartDate().format(DATE_FORMATTER),
                c.getEndDate().format(DATE_FORMATTER),
                CURRENCY_FORMAT.format(c.getMonthlyRent()),
                CURRENCY_FORMAT.format(c.getDeposit())
        );

        messageSender.sendMessage(chatId, memberText);
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
            String clean = text.trim().replaceAll("[^0-9.]", "");
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
