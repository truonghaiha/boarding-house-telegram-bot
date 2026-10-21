package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.service.MeterReadingService;
import com.boardinghousebot.service.RoomService;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class MeterCommandHandler implements CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(MeterCommandHandler.class);
    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(new Locale("vi", "VN"));

    @org.springframework.beans.factory.annotation.Autowired
    private com.boardinghousebot.service.UtilityRateService utilityRateService;

    @org.springframework.beans.factory.annotation.Autowired
    private UtilityCycleFlow cycleFlow;

    private final MeterReadingService meterReadingService;
    private final RoomService roomService;
    private final UserService userService;
    private final ConversationStateService stateService;

    public MeterCommandHandler(MeterReadingService meterReadingService,
                               RoomService roomService,
                               UserService userService,
                               ConversationStateService stateService) {
        this.meterReadingService = meterReadingService;
        this.roomService = roomService;
        this.userService = userService;
        this.stateService = stateService;
    }

    @Override
    public String getCommand() {
        return "/meter";
    }

    @Override
    public String getDescription() {
        return "Quản lý chỉ số điện nước";
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

        if (cycleFlow != null && !"MEMBER_METER_LEGACY".equals(text) && !"METER_LEGACY_LIST".equals(text)
                && cycleFlow.meter(chatId,user,text,state,messageSender)) return;
        if ("MEMBER_METER_LEGACY".equals(text)) { showMemberMeterReadings(chatId,user,messageSender,null); return; }
        if ("METER_LEGACY_LIST".equals(text)) { listLatestReadings(chatId,user,messageSender); return; }
        log.info("METER STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), text);

        if ("/cancel".equalsIgnoreCase(text) || "CANCEL_CREATE_METER".equals(text) || "CANCEL_UPDATE_METER".equals(text)) {
            boolean isUpdate = "CANCEL_UPDATE_METER".equals(text) || state.getStep().name().startsWith("UPDATE_METER");
            stateService.clearState(user.getTelegramId());
            String cancelMessage = state.getStep().name().startsWith("UPDATE_UTILITY") ? "❌ Đã hủy cập nhật đơn giá cả tòa." : isUpdate ? "❌ Đã hủy thao tác sửa chỉ số điện nước." : "❌ Đã hủy thao tác ghi chỉ số điện nước.";
            messageSender.sendMessageWithKeyboard(chatId, cancelMessage, TelegramKeyboardUtil.getLandlordMeterManagementMenu());
            return;
        }

        if (text.startsWith("METER_RATE_") || text.equals("METER_RATE_CONFIRM")
                || state.getStep().name().startsWith("UPDATE_UTILITY")) {
            handleUtilityRate(chatId, user, text, state, messageSender);
            return;
        }

        // Inline button action callbacks
        if (text.startsWith("METER_SELECT_ROOM_")) {
            handleRoomSelected(chatId, user, text, state, messageSender);
            return;
        }

        if (text.startsWith("METER_SELECT_TYPE_")) {
            handleTypeSelected(chatId, user, text, state, messageSender);
            return;
        }

        if ("CONFIRM_CREATE_METER".equals(text)) {
            executeCreateMeter(chatId, user, state, messageSender);
            return;
        }

        if ("CONFIRM_UPDATE_METER".equals(text)) {
            executeUpdateMeter(chatId, user, state, messageSender);
            return;
        }

        if (text.startsWith("METER_EDIT_")) {
            Long readingId = Long.parseLong(text.replace("METER_EDIT_", "").trim());
            startUpdateMeterFlow(chatId, user, readingId, state, messageSender);
            return;
        }

        if (text.startsWith("METER_DELETE_")) {
            Long readingId = Long.parseLong(text.replace("METER_DELETE_", "").trim());
            promptDeleteConfirmation(chatId, user, readingId, messageSender);
            return;
        }

        if (text.startsWith("CONFIRM_DELETE_METER_")) {
            Long readingId = Long.parseLong(text.replace("CONFIRM_DELETE_METER_", "").trim());
            executeDeleteMeter(chatId, user, readingId, messageSender);
            return;
        }

        // Active conversation step for text inputs
        if (state.getStep() != UserConversationState.Step.NONE) {
            handleConversationStep(chatId, user, text, state, messageSender);
            return;
        }

        // Non-state commands & callbacks
        if (text.startsWith("METER_DETAIL_") || text.startsWith("/meter_detail_")) {
            String idStr = text.startsWith("METER_DETAIL_") ? text.replace("METER_DETAIL_", "") : text.replace("/meter_detail_", "");
            showMeterDetail(chatId, user, Long.parseLong(idStr.trim()), messageSender);
            return;
        }

        if (text.startsWith("METER_F_MONTH_")) {
            String val = text.replace("METER_F_MONTH_", "").trim();
            if ("ALL".equals(val)) {
                state.getData().remove("meterFilterMonth");
                state.getData().remove("meterFilterYear");
            } else {
                String[] parts = val.split("_");
                state.getData().put("meterFilterMonth", Integer.parseInt(parts[0]));
                state.getData().put("meterFilterYear", Integer.parseInt(parts[1]));
            }
            stateService.setState(user.getTelegramId(), state);
            executeSearchWithState(chatId, user, state, messageSender);
            return;
        }

        if (text.startsWith("METER_F_ROOM_")) {
            String val = text.replace("METER_F_ROOM_", "").trim();
            if ("ALL".equals(val)) {
                state.getData().remove("meterFilterRoomId");
            } else {
                state.getData().put("meterFilterRoomId", Long.parseLong(val));
            }
            stateService.setState(user.getTelegramId(), state);
            executeSearchWithState(chatId, user, state, messageSender);
            return;
        }

        if (text.startsWith("METER_F_TYPE_")) {
            String val = text.replace("METER_F_TYPE_", "").trim();
            if ("ALL".equals(val)) {
                state.getData().remove("meterFilterType");
            } else {
                state.getData().put("meterFilterType", val);
            }
            stateService.setState(user.getTelegramId(), state);
            executeSearchWithState(chatId, user, state, messageSender);
            return;
        }

        if ("METER_FILTER_CLEAR".equals(text)) {
            state.getData().remove("meterFilterMonth");
            state.getData().remove("meterFilterYear");
            state.getData().remove("meterFilterRoomId");
            state.getData().remove("meterFilterType");
            stateService.setState(user.getTelegramId(), state);
            listLatestReadings(chatId, user, messageSender);
            return;
        }

        if ("METER_FILTER_SELECT_MONTH".equals(text)) {
            showMonthSelectionKeyboard(chatId, user, messageSender);
            return;
        }

        if ("METER_FILTER_SELECT_ROOM".equals(text)) {
            showRoomFilterSelectionKeyboard(chatId, user, messageSender);
            return;
        }

        if ("METER_FILTER_SELECT_TYPE".equals(text)) {
            showTypeFilterSelectionKeyboard(chatId, user, messageSender);
            return;
        }

        switch (text) {
            case "LANDLORD_METERS", "METER_MENU", "/meter" -> showMeterMenu(chatId, user, messageSender);
            case "METER_ADD" -> startCreateMeterFlow(chatId, user, state, messageSender);
            case "METER_LIST_ALL", "METER_LIST_LATEST" -> listLatestReadings(chatId, user, messageSender);
            case "MEMBER_METERS", "MEMBER_METER_ALL" -> showMemberMeterReadings(chatId, user, messageSender, null);
            case "MEMBER_METER_ELECTRICITY" -> showMemberMeterReadings(chatId, user, messageSender, UtilityType.ELECTRICITY);
            case "MEMBER_METER_WATER" -> showMemberMeterReadings(chatId, user, messageSender, UtilityType.WATER);
            default -> showMeterMenu(chatId, user, messageSender);
        }
    }

    private void showMeterMenu(Long chatId, User user, BotMessageSender messageSender) {
        if (user.getRole() == Role.MEMBER) {
            showMemberMeterReadings(chatId, user, messageSender, null);
            return;
        }
        messageSender.sendMessageWithKeyboard(chatId, "⚡ QUẢN LÝ ĐIỆN NƯỚC\n\n"
                + "⚡ Giá điện cả tòa: " + roomService.utilityPriceDisplay(UtilityType.ELECTRICITY)
                + "\n💧 Giá nước cả tòa: " + roomService.utilityPriceDisplay(UtilityType.WATER)
                + "\n\nGhi chỉ số và chốt tiền điện nước theo từng phòng.", TelegramKeyboardUtil.getLandlordMeterManagementMenu());
    }

    private void startCreateMeterFlow(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        if (user.getRole() != Role.LANDLORD) {
            messageSender.sendNotice(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }

        List<Room> rooms = roomService.getAllRooms();
        if (rooms.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "⚡ GHI CHỈ SỐ ĐIỆN NƯỚC\n\nChưa có phòng nào trong hệ thống.", TelegramKeyboardUtil.getLandlordMeterManagementMenu());
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (Room r : rooms) {
            String label = "🏠 Phòng " + r.getRoomNumber();
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "METER_SELECT_ROOM_" + r.getId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_CREATE_METER")));

        state.setStep(UserConversationState.Step.CREATE_METER_ROOM_SELECT);
        stateService.setState(user.getTelegramId(), state);
        messageSender.sendMessageWithKeyboard(chatId, "🏠 CHỌN PHÒNG ĐỂ GHI CHỈ SỐ:", new InlineKeyboardMarkup(rows));
    }

    private void handleRoomSelected(Long chatId, User user, String text, UserConversationState state, BotMessageSender messageSender) {
        Long roomId = Long.parseLong(text.replace("METER_SELECT_ROOM_", "").trim());
        state.getData().put("roomId", roomId);
        state.setStep(UserConversationState.Step.CREATE_METER_TYPE_SELECT);
        stateService.setState(user.getTelegramId(), state);

        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("⚡ Điện", "METER_SELECT_TYPE_ELECTRICITY"),
                        TelegramKeyboardUtil.createButton("💧 Nước", "METER_SELECT_TYPE_WATER")
                ),
                new InlineKeyboardRow(TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_CREATE_METER"))
        );

        messageSender.sendMessageWithKeyboard(chatId, "⚡ CHỌN LOẠI DỊCH VỤ:", new InlineKeyboardMarkup(rows));
    }

    private void handleTypeSelected(Long chatId, User user, String text, UserConversationState state, BotMessageSender messageSender) {
        UtilityType type = text.endsWith("ELECTRICITY") ? UtilityType.ELECTRICITY : UtilityType.WATER;
        state.getData().put("utilityType", type.name());
        state.setStep(UserConversationState.Step.CREATE_METER_MONTH);
        stateService.setState(user.getTelegramId(), state);

        int currentMonth = LocalDateTime.now().getMonthValue();
        messageSender.sendPrompt(chatId, "📅 Nhập tháng ghi chỉ số (1–12, ví dụ: " + currentMonth + "):");
    }

    private void handleConversationStep(Long chatId, User user, String input, UserConversationState state, BotMessageSender messageSender) {
        log.info("METER STATE ROUTING: telegramId={}, step={}, input={}", user.getTelegramId(), state.getStep(), input);

        switch (state.getStep()) {
            case CREATE_METER_ROOM_SELECT -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng chọn phòng từ danh sách phím bấm bên dưới.");
            }
            case CREATE_METER_TYPE_SELECT -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng chọn [⚡ Điện] hoặc [💧 Nước] từ phím bấm bên dưới.");
            }
            case CREATE_METER_MONTH -> {
                Integer month = parseInteger(input);
                if (month == null || month < 1 || month > 12) {
                    messageSender.sendNotice(chatId, "❌ Tháng không hợp lệ (phải từ 1 đến 12). Vui lòng nhập lại tháng:");
                    return;
                }
                state.getData().put("month", month);
                state.setStep(UserConversationState.Step.CREATE_METER_YEAR);
                stateService.setState(user.getTelegramId(), state);

                int currentYear = LocalDateTime.now().getYear();
                messageSender.sendPrompt(chatId, "📅 Nhập năm ghi chỉ số (ví dụ: " + currentYear + "):");
            }
            case CREATE_METER_YEAR -> {
                Integer year = parseInteger(input);
                if (year == null || year < 2000 || year > 2100) {
                    messageSender.sendNotice(chatId, "❌ Năm không hợp lệ. Vui lòng nhập lại năm (ví dụ: 2026):");
                    return;
                }
                state.getData().put("year", year);
                state.setStep(UserConversationState.Step.CREATE_METER_PREVIOUS);
                stateService.setState(user.getTelegramId(), state);

                UtilityType type = UtilityType.valueOf((String) state.getData().get("utilityType"));
                messageSender.sendPrompt(chatId, "📊 Nhập chỉ số cũ (" + type.getUnit() + ", ví dụ: 1200):");
            }
            case CREATE_METER_PREVIOUS -> {
                BigDecimal previous = parseBigDecimal(input);
                if (previous == null || previous.compareTo(BigDecimal.ZERO) < 0) {
                    messageSender.sendNotice(chatId, "❌ Chỉ số cũ phải là số không âm (>= 0). Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("previous", previous);
                state.setStep(UserConversationState.Step.CREATE_METER_CURRENT);
                stateService.setState(user.getTelegramId(), state);

                UtilityType type = UtilityType.valueOf((String) state.getData().get("utilityType"));
                messageSender.sendPrompt(chatId, "📊 Nhập chỉ số mới (" + type.getUnit() + ", ví dụ: 1350):");
            }
            case CREATE_METER_CURRENT -> {
                BigDecimal current = parseBigDecimal(input);
                BigDecimal previous = (BigDecimal) state.getData().get("previous");
                if (current == null || current.compareTo(BigDecimal.ZERO) < 0) {
                    messageSender.sendNotice(chatId, "❌ Chỉ số mới phải là số không âm (>= 0). Vui lòng nhập lại:");
                    return;
                }
                if (current.compareTo(previous) < 0) {
                    messageSender.sendNotice(chatId, "❌ Chỉ số mới phải lớn hơn hoặc bằng chỉ số cũ (>= " + previous + "). Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("current", current);
                UtilityType type = UtilityType.valueOf((String) state.getData().get("utilityType"));
                try {
                    BigDecimal price = meterReadingService.getPeriodUnitPrice(type,
                            (Integer) state.getData().get("month"), (Integer) state.getData().get("year"));
                    state.getData().put("unitPrice", price);
                    state.setStep(UserConversationState.Step.CREATE_METER_CONFIRM);
                    stateService.setState(user.getTelegramId(), state);
                    showMeterCreatePreview(chatId, state, messageSender);
                } catch (BusinessException e) {
                    messageSender.sendNotice(chatId, "❌ " + e.getMessage());
                }
            }
            case CREATE_METER_UNIT_PRICE -> {
                stateService.clearState(user.getTelegramId());
                messageSender.sendNotice(chatId, "Đơn giá hiện được quản lý chung trong Điện nước. Vui lòng bắt đầu ghi/sửa chỉ số lại.");
                showMeterMenu(chatId, user, messageSender);
            }
            case CREATE_METER_CONFIRM -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            case UPDATE_METER_PREVIOUS -> {
                BigDecimal previous = parseBigDecimal(input);
                if (previous == null || previous.compareTo(BigDecimal.ZERO) < 0) {
                    messageSender.sendNotice(chatId, "❌ Chỉ số cũ phải là số không âm (>= 0). Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("previous", previous);
                state.setStep(UserConversationState.Step.UPDATE_METER_CURRENT);
                stateService.setState(user.getTelegramId(), state);

                UtilityType type = UtilityType.valueOf((String) state.getData().get("utilityType"));
                messageSender.sendPrompt(chatId, "📊 Nhập chỉ số mới (" + type.getUnit() + "):");
            }
            case UPDATE_METER_CURRENT -> {
                BigDecimal current = parseBigDecimal(input);
                BigDecimal previous = (BigDecimal) state.getData().get("previous");
                if (current == null || current.compareTo(BigDecimal.ZERO) < 0 || current.compareTo(previous) < 0) {
                    messageSender.sendNotice(chatId, "❌ Chỉ số mới phải lớn hơn hoặc bằng chỉ số cũ. Vui lòng nhập lại:");
                    return;
                }
                state.getData().put("current", current);
                MeterReading saved = meterReadingService.getReadingById((Long) state.getData().get("readingId"));
                state.getData().put("unitPrice", saved.getUnitPrice());
                state.setStep(UserConversationState.Step.UPDATE_METER_CONFIRM);
                stateService.setState(user.getTelegramId(), state);
                showMeterUpdatePreview(chatId, state, messageSender);
            }
            case UPDATE_METER_UNIT_PRICE -> {
                stateService.clearState(user.getTelegramId());
                messageSender.sendNotice(chatId, "Đơn giá hiện được quản lý chung trong Điện nước. Vui lòng bắt đầu ghi/sửa chỉ số lại.");
                showMeterMenu(chatId, user, messageSender);
            }
            case UPDATE_METER_CONFIRM -> {
                messageSender.sendNotice(chatId, "❌ Vui lòng bấm phím [✅ Xác nhận] hoặc [❌ Hủy] bên dưới.");
            }
            default -> {
                stateService.clearState(user.getTelegramId());
                showMeterMenu(chatId, user, messageSender);
            }
        }
    }

    private void showMeterCreatePreview(Long chatId, UserConversationState state, BotMessageSender messageSender) {
        Long roomId = (Long) state.getData().get("roomId");
        Room room = roomService.getRoomById(roomId);
        UtilityType type = UtilityType.valueOf((String) state.getData().get("utilityType"));
        Integer month = (Integer) state.getData().get("month");
        Integer year = (Integer) state.getData().get("year");
        BigDecimal previous = (BigDecimal) state.getData().get("previous");
        BigDecimal current = (BigDecimal) state.getData().get("current");
        BigDecimal unitPrice = (BigDecimal) state.getData().get("unitPrice");

        BigDecimal consumption = current.subtract(previous);
        BigDecimal totalCharge = meterReadingService.calculateCharge(type, consumption, unitPrice);

        String previewText = String.format("""
                %s XÁC NHẬN GHI CHỈ SỐ %s

                🏠 Phòng: %s
                %s Loại: %s
                📅 Kỳ: %02d/%d

                📊 Chỉ số cũ: %s %s
                📊 Chỉ số mới: %s %s
                📈 Tiêu thụ: %s %s
                💰 Đơn giá: %s/%s
                💵 Thành tiền: %s
                """,
                type.getIcon(),
                type.getDisplayName().toUpperCase(),
                room.getRoomNumber(),
                type.getIcon(),
                type.getDisplayName(),
                month, year,
                formatNumber(previous), type.getUnit(),
                formatNumber(current), type.getUnit(),
                formatNumber(consumption), type.getUnit(),
                CURRENCY_FORMAT.format(unitPrice), type.getUnit(),
                CURRENCY_FORMAT.format(totalCharge)
        );

        messageSender.sendMessageWithKeyboard(chatId, previewText, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_CREATE_METER", "CANCEL_CREATE_METER"));
    }

    private void showMeterUpdatePreview(Long chatId, UserConversationState state, BotMessageSender messageSender) {
        Long readingId = (Long) state.getData().get("readingId");
        UtilityType type = UtilityType.valueOf((String) state.getData().get("utilityType"));
        String roomNumber = (String) state.getData().get("roomNumber");
        Integer month = (Integer) state.getData().get("month");
        Integer year = (Integer) state.getData().get("year");
        BigDecimal previous = (BigDecimal) state.getData().get("previous");
        BigDecimal current = (BigDecimal) state.getData().get("current");
        BigDecimal unitPrice = (BigDecimal) state.getData().get("unitPrice");

        BigDecimal consumption = current.subtract(previous);
        BigDecimal totalCharge = meterReadingService.calculateCharge(type, consumption, unitPrice);

        String previewText = String.format("""
                ✏️ XÁC NHẬN CẬP NHẬT CHỈ SỐ #%d

                🏠 Phòng: %s (%s, %02d/%d)
                📊 Chỉ số cũ mới: %s %s
                📊 Chỉ số mới mới: %s %s
                📈 Tiêu thụ mới: %s %s
                💰 Đơn giá mới: %s/%s
                💵 Thành tiền mới: %s
                """,
                readingId,
                roomNumber, type.getDisplayName(), month, year,
                formatNumber(previous), type.getUnit(),
                formatNumber(current), type.getUnit(),
                formatNumber(consumption), type.getUnit(),
                CURRENCY_FORMAT.format(unitPrice), type.getUnit(),
                CURRENCY_FORMAT.format(totalCharge)
        );

        messageSender.sendMessageWithKeyboard(chatId, previewText, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_UPDATE_METER", "CANCEL_UPDATE_METER"));
    }

    private void executeCreateMeter(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        try {
            if (state.getStep() != UserConversationState.Step.CREATE_METER_CONFIRM)
                throw new BusinessException("Thao tác đã hết hiệu lực. Vui lòng ghi chỉ số lại.");
            Long roomId = (Long) state.getData().get("roomId");
            UtilityType type = UtilityType.valueOf((String) state.getData().get("utilityType"));
            Integer month = (Integer) state.getData().get("month");
            Integer year = (Integer) state.getData().get("year");
            BigDecimal previous = (BigDecimal) state.getData().get("previous");
            BigDecimal current = (BigDecimal) state.getData().get("current");
            BigDecimal unitPrice = (BigDecimal) state.getData().get("unitPrice");

            BigDecimal latestPrice = meterReadingService.getPeriodUnitPrice(type, month, year);
            if (latestPrice.compareTo(unitPrice) != 0) {
                state.getData().put("unitPrice", latestPrice);
                messageSender.sendNotice(chatId, "Đơn giá vừa thay đổi. Vui lòng xem và xác nhận lại.");
                showMeterCreatePreview(chatId, state, messageSender);
                return;
            }
            MeterReading reading = meterReadingService.createReading(user.getTelegramId(), roomId, type, month, year, previous, current);

            stateService.clearState(user.getTelegramId());
            messageSender.sendResultAndMenu(chatId, String.format("✅ Đã ghi chỉ số %s thành công cho phòng %s (Kỳ %02d/%d)!",
                    type.getDisplayName().toLowerCase(), reading.getRoom().getRoomNumber(), month, year), TelegramKeyboardUtil.getLandlordMeterManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void startUpdateMeterFlow(Long chatId, User user, Long readingId, UserConversationState state, BotMessageSender messageSender) {
        MeterReading reading = meterReadingService.getReadingById(readingId);

        state.getData().put("readingId", readingId);
        state.getData().put("utilityType", reading.getUtilityType().name());
        state.getData().put("roomNumber", reading.getRoom().getRoomNumber());
        state.getData().put("month", reading.getBillingMonth());
        state.getData().put("year", reading.getBillingYear());
        state.setStep(UserConversationState.Step.UPDATE_METER_PREVIOUS);
        stateService.setState(user.getTelegramId(), state);

        messageSender.sendPrompt(chatId, String.format("✏️ SỬA CHỈ SỐ %s PHÒNG %s (KỲ %02d/%d)\n\nNhập chỉ số cũ mới (%s):",
                reading.getUtilityType().getDisplayName().toUpperCase(),
                reading.getRoom().getRoomNumber(),
                reading.getBillingMonth(), reading.getBillingYear(),
                reading.getUtilityType().getUnit()));
    }

    private void executeUpdateMeter(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        try {
            Long readingId = (Long) state.getData().get("readingId");
            BigDecimal previous = (BigDecimal) state.getData().get("previous");
            BigDecimal current = (BigDecimal) state.getData().get("current");
            BigDecimal unitPrice = (BigDecimal) state.getData().get("unitPrice");

            MeterReading updated = meterReadingService.updateReading(user.getTelegramId(), readingId, previous, current);

            stateService.clearState(user.getTelegramId());
            messageSender.sendResultAndMenu(chatId, String.format("✅ Đã cập nhật chỉ số %s #%d thành công!",
                    updated.getUtilityType().getDisplayName().toLowerCase(), updated.getId()), TelegramKeyboardUtil.getLandlordMeterManagementMenu());
        } catch (BusinessException e) {
            stateService.clearState(user.getTelegramId());
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void promptDeleteConfirmation(Long chatId, User user, Long readingId, BotMessageSender messageSender) {
        MeterReading reading = meterReadingService.getReadingById(readingId);
        UtilityType type = reading.getUtilityType();

        String promptText = String.format("""
                ⚠️ XÁC NHẬN XÓA CHỈ SỐ %s?

                🏠 Phòng: %s
                %s Loại: %s
                📅 Kỳ: %02d/%d
                📊 Chỉ số: %s → %s (%s %s)
                💵 Thành tiền: %s

                Hành động này không thể hoàn tác.
                """,
                type.getDisplayName().toUpperCase(),
                reading.getRoom().getRoomNumber(),
                type.getIcon(),
                type.getDisplayName(),
                reading.getBillingMonth(), reading.getBillingYear(),
                formatNumber(reading.getPreviousReading()), formatNumber(reading.getCurrentReading()),
                formatNumber(reading.getConsumption()), type.getUnit(),
                CURRENCY_FORMAT.format(reading.getTotalCharge())
        );

        messageSender.sendMessageWithKeyboard(chatId, promptText, TelegramKeyboardUtil.getConfirmKeyboard("CONFIRM_DELETE_METER_" + readingId, "METER_DETAIL_" + readingId));
    }

    private void executeDeleteMeter(Long chatId, User user, Long readingId, BotMessageSender messageSender) {
        try {
            meterReadingService.deleteReading(user.getTelegramId(), readingId);
            messageSender.sendResultAndMenu(chatId, "✅ Đã xóa bản ghi chỉ số thành công.", TelegramKeyboardUtil.getLandlordMeterManagementMenu());
        } catch (BusinessException e) {
            messageSender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private void listLatestReadings(Long chatId, User user, BotMessageSender messageSender) {
        int[] latest = meterReadingService.getLatestBillingPeriod();
        UserConversationState state = stateService.getState(user.getTelegramId());
        state.getData().put("meterFilterMonth", latest[0]);
        state.getData().put("meterFilterYear", latest[1]);
        state.getData().remove("meterFilterRoomId");
        state.getData().remove("meterFilterType");
        stateService.setState(user.getTelegramId(), state);
        List<MeterReading> readings = meterReadingService.getFilteredReadings(null, null, latest[0], latest[1]);
        renderReadingsGroupedByRoom(chatId, messageSender, readings, latest[0], latest[1]);
    }

    private void executeSearchWithState(Long chatId, User user, UserConversationState state, BotMessageSender messageSender) {
        Integer month = (Integer) state.getData().get("meterFilterMonth");
        Integer year = (Integer) state.getData().get("meterFilterYear");
        Long roomId = (Long) state.getData().get("meterFilterRoomId");
        String typeStr = (String) state.getData().get("meterFilterType");
        UtilityType type = typeStr == null ? null : UtilityType.valueOf(typeStr);

        List<MeterReading> readings = meterReadingService.getFilteredReadings(roomId, type, month, year);
        renderReadingsGroupedByRoom(chatId, messageSender, readings, month, year);
    }

    private void renderReadingsGroupedByRoom(Long chatId, BotMessageSender messageSender,
                                             List<MeterReading> readings, Integer month, Integer year) {
        String title;
        if (month != null && year != null) {
            title = String.format("⚡ CHỈ SỐ THÁNG %02d/%d", month, year);
        } else {
            title = "🔎 KẾT QUẢ TRA CỨU CHỈ SỐ ĐIỆN NƯỚC";
        }

        if (readings.isEmpty()) {
            String emptyMsg = title + "\n\nChưa có dữ liệu chỉ số cho lựa chọn này.";
            messageSender.sendMessageWithKeyboard(chatId, emptyMsg, TelegramKeyboardUtil.getMeterFilterKeyboard());
            return;
        }

        // Group readings by room number
        java.util.Map<String, List<MeterReading>> grouped = new java.util.LinkedHashMap<>();
        for (MeterReading r : readings) {
            String roomNum = r.getRoom().getRoomNumber();
            grouped.computeIfAbsent(roomNum, k -> new ArrayList<>()).add(r);
        }

        StringBuilder sb = new StringBuilder(title + "\n\n");
        for (java.util.Map.Entry<String, List<MeterReading>> entry : grouped.entrySet()) {
            sb.append("🏠 Phòng ").append(entry.getKey()).append("\n");
            for (MeterReading r : entry.getValue()) {
                UtilityType t = r.getUtilityType();
                sb.append("   ").append(t.getIcon()).append(" ").append(t.getDisplayName()).append(": ")
                        .append(formatNumber(r.getPreviousReading())).append(" → ").append(formatNumber(r.getCurrentReading()))
                        .append(" (").append(formatNumber(r.getConsumption())).append(" ").append(t.getUnit()).append(")")
                        .append(" - 💰 ").append(CURRENCY_FORMAT.format(r.getTotalCharge())).append("\n");
            }
            sb.append("\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getDetailListKeyboard(readings,
                r -> r.getUtilityType().getIcon() + " #" + r.getId() + " – P" + r.getRoom().getRoomNumber(),
                r -> "METER_DETAIL_" + r.getId(), TelegramKeyboardUtil.getMeterFilterKeyboard()));
    }

    private void showMonthSelectionKeyboard(Long chatId, User user, BotMessageSender messageSender) {
        List<int[]> periods = meterReadingService.getAvailableBillingPeriods();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("📅 Tất cả các kỳ", "METER_F_MONTH_ALL")));

        for (int[] p : periods) {
            String label = String.format("📅 Kỳ %02d/%d", p[0], p[1]);
            String cb = String.format("METER_F_MONTH_%d_%d", p[0], p[1]);
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, cb)));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Quay lại", "METER_LIST_LATEST")));

        messageSender.sendMessageWithKeyboard(chatId, "📅 CHỌN KỲ GHI ĐỂ LỌC:", new InlineKeyboardMarkup(rows));
    }

    private void showRoomFilterSelectionKeyboard(Long chatId, User user, BotMessageSender messageSender) {
        List<Room> rooms = roomService.getAllRooms();
        List<InlineKeyboardRow> rows = new ArrayList<>();

        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🏠 Tất cả phòng", "METER_F_ROOM_ALL")));

        for (Room r : rooms) {
            String label = "🏠 Phòng " + r.getRoomNumber();
            rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton(label, "METER_F_ROOM_" + r.getId())));
        }
        rows.add(new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Quay lại", "METER_LIST_LATEST")));

        messageSender.sendMessageWithKeyboard(chatId, "🏠 CHỌN PHÒNG ĐỂ LỌC:", new InlineKeyboardMarkup(rows));
    }

    private void showTypeFilterSelectionKeyboard(Long chatId, User user, BotMessageSender messageSender) {
        List<InlineKeyboardRow> rows = List.of(
                new InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("⚡ Chỉ số Điện", "METER_F_TYPE_ELECTRICITY"),
                        TelegramKeyboardUtil.createButton("💧 Chỉ số Nước", "METER_F_TYPE_WATER")
                ),
                new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🌐 Tất cả loại", "METER_F_TYPE_ALL")),
                new InlineKeyboardRow(TelegramKeyboardUtil.createButton("🔙 Quay lại", "METER_LIST_LATEST"))
        );

        messageSender.sendMessageWithKeyboard(chatId, "⚡ CHỌN LOẠI DỊCH VỤ ĐỂ LỌC:", new InlineKeyboardMarkup(rows));
    }

    private void showMemberMeterReadings(Long chatId, User user, BotMessageSender messageSender, UtilityType typeFilter) {
        List<MeterReading> readings = meterReadingService.getMemberRoomReadings(user.getTelegramId());

        if (user.getRoom() == null && readings.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, "⚡ ĐIỆN NƯỚC PHÒNG TÔI\n\nBạn hiện chưa được xếp vào phòng nào.", TelegramKeyboardUtil.getMemberMeterMenu());
            return;
        }

        if (typeFilter != null) {
            readings = readings.stream().filter(r -> r.getUtilityType() == typeFilter).toList();
        }

        String title = "⚡ ĐIỆN NƯỚC ĐƯỢC PHÉP XEM";
        if (readings.isEmpty()) {
            messageSender.sendMessageWithKeyboard(chatId, title + "\n\nChưa có dữ liệu chỉ số điện nước.", TelegramKeyboardUtil.getMemberMeterMenu());
            return;
        }

        StringBuilder sb = new StringBuilder(title + "\n\n");
        int idx = 1;
        for (MeterReading r : readings) {
            UtilityType t = r.getUtilityType();
            sb.append("🏠 Phòng: ").append(r.getRoom().getRoomNumber()).append("\n");
            sb.append(idx++).append(". ").append(t.getIcon()).append(" ").append(t.getDisplayName()).append(" (Kỳ ").append(String.format("%02d/%d", r.getBillingMonth(), r.getBillingYear())).append(")\n");
            sb.append("   📊 Chỉ số cũ: ").append(formatNumber(r.getPreviousReading())).append(" ").append(t.getUnit()).append("\n");
            sb.append("   📊 Chỉ số mới: ").append(formatNumber(r.getCurrentReading())).append(" ").append(t.getUnit()).append("\n");
            sb.append("   📈 Tiêu thụ: ").append(formatNumber(r.getConsumption())).append(" ").append(t.getUnit()).append("\n");
            sb.append("   💰 Đơn giá: ").append(CURRENCY_FORMAT.format(r.getUnitPrice())).append("/").append(t.getUnit()).append("\n");
            sb.append("   💵 Thành tiền: ").append(CURRENCY_FORMAT.format(r.getTotalCharge())).append("\n\n");
        }

        messageSender.sendMessageWithKeyboard(chatId, sb.toString().trim(), TelegramKeyboardUtil.getMemberMeterMenu());
    }

    private void showMeterDetail(Long chatId, User user, Long readingId, BotMessageSender messageSender) {
        MeterReading r = meterReadingService.getReadingForUser(user, readingId);
        UtilityType t = r.getUtilityType();

        String detailText = String.format("""
                %s CHI TIẾT CHỈ SỐ #%d

                🏠 Phòng: %s
                %s Loại: %s
                📅 Kỳ ghi: %02d/%d

                📊 Chỉ số cũ: %s %s
                📊 Chỉ số mới: %s %s

                📈 Tiêu thụ: %s %s
                💰 Đơn giá: %s/%s
                💵 Thành tiền: %s
                """,
                t.getIcon(),
                r.getId(),
                r.getRoom().getRoomNumber(),
                t.getIcon(),
                t.getDisplayName(),
                r.getBillingMonth(), r.getBillingYear(),
                formatNumber(r.getPreviousReading()), t.getUnit(),
                formatNumber(r.getCurrentReading()), t.getUnit(),
                formatNumber(r.getConsumption()), t.getUnit(),
                CURRENCY_FORMAT.format(r.getUnitPrice()), t.getUnit(),
                CURRENCY_FORMAT.format(r.getTotalCharge())
        );

        if (user.getRole() == Role.LANDLORD) {
            messageSender.sendMessageWithKeyboard(chatId, detailText, TelegramKeyboardUtil.getMeterDetailKeyboard(r.getId()));
        } else {
            messageSender.sendMessageWithKeyboard(chatId, detailText, TelegramKeyboardUtil.getMemberMeterMenu());
        }
    }

    private String formatNumber(BigDecimal val) {
        if (val == null) return "0";
        if (val.stripTrailingZeros().scale() <= 0) {
            return String.format(Locale.US, "%,d", val.longValue());
        }
        return String.format(Locale.US, "%,.2f", val.doubleValue());
    }

    private Integer parseInteger(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
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
    private void handleUtilityRate(Long chatId, User user, String action, UserConversationState state, BotMessageSender sender) {
        if (user.getRole() != Role.LANDLORD) {
            sender.sendNotice(chatId, "❌ Bạn không có quyền thực hiện chức năng này.");
            return;
        }
        try {
            if (action.equals("METER_RATE_ELECTRICITY") || action.equals("METER_RATE_WATER")) {
                UtilityType type = action.endsWith("ELECTRICITY") ? UtilityType.ELECTRICITY : UtilityType.WATER;
                state.getData().clear();
                state.setStep(UserConversationState.Step.UPDATE_UTILITY_PRICE);
                state.getData().put("rateType", type);
                stateService.setState(user.getTelegramId(), state);
                sender.sendPrompt(chatId, (type==UtilityType.ELECTRICITY?"⚡":"💧")+" CẬP NHẬT GIÁ " + type.getDisplayName().toUpperCase() + " CẢ TÒA"
                        + "\n\nGiá hiện tại: " + currentUtilityRateText(type)
                        + "\n\nNhập giá mới (VNĐ/" + type.getUnit() + "):"
                        + "\n\nÁp dụng từ kỳ tiếp theo của từng phòng.");
                return;
            }
            if (action.equals("METER_RATE_CONFIRM")) {
                if (state.getStep() != UserConversationState.Step.UPDATE_UTILITY_CONFIRM)
                    throw new BusinessException("Thao tác đã hết hiệu lực. Vui lòng cập nhật giá lại.");
                utilityRateService.update(user.getTelegramId(), (UtilityType) state.getData().get("rateType"),
                        (BigDecimal) state.getData().get("ratePrice"));
                stateService.clearState(user.getTelegramId());
                sender.sendNotice(chatId, "✅ Đã lưu đơn giá chung và thông báo cho người đang thuê.");
                showMeterMenu(chatId, user, sender);
                return;
            }
            if (state.getStep() != UserConversationState.Step.UPDATE_UTILITY_PRICE) {
                sender.sendNotice(chatId, "Vui lòng xác nhận hoặc hủy bằng nút bên dưới.");
                return;
            }
            BigDecimal price = new BigDecimal(action.trim());
            if (price.signum() <= 0 || price.scale() > 2 || price.precision() > 12)
                throw new BusinessException("Đơn giá phải lớn hơn 0, tối đa 2 chữ số thập phân.");
            UtilityType type = (UtilityType) state.getData().get("rateType");
            state.getData().put("ratePrice", price);
            state.setStep(UserConversationState.Step.UPDATE_UTILITY_CONFIRM);
            stateService.setState(user.getTelegramId(), state);
            sender.sendMessageWithKeyboard(chatId, "✅ XÁC NHẬN GIÁ " + type.getDisplayName().toUpperCase() + " CẢ TÒA"
                    + "\n\nGiá cũ: " + currentUtilityRateText(type)
                    + "\nGiá mới: " + utilityRateText(price,type)
                    + "\n\nÁp dụng từ kỳ tiếp theo của từng phòng."
                    + "\nNgười thuê sẽ nhận được thông báo khi xác nhận.",
                    TelegramKeyboardUtil.getConfirmKeyboard("METER_RATE_CONFIRM", "/cancel"));
        } catch (NumberFormatException e) {
            sender.sendNotice(chatId, "❌ Vui lòng nhập đơn giá bằng số.");
        } catch (BusinessException e) {
            sender.sendNotice(chatId, "❌ " + e.getMessage());
        }
    }

    private String currentUtilityRateText(UtilityType type) {
        return utilityRateService.current(type).map(rate -> utilityRateText(rate.getUnitPrice(),type)).orElse("Chưa thiết lập");
    }

    private String utilityRateText(BigDecimal price, UtilityType type) {
        return NumberFormat.getNumberInstance(new Locale("vi","VN")).format(price)+" đ/"+type.getUnit();
    }
}
