package com.boardinghousebot.bot.ui;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.*;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotUiSessionManagerTest {
    private final TelegramClient client = mock(TelegramClient.class);
    private final BotMessageSender events = mock(BotMessageSender.class);
    private final ConversationStateService states = new ConversationStateService();
    private final BotUiSessionManager manager = new BotUiSessionManager(() -> client);
    private final User user = new User(123L, "owner", "Owner", Role.LANDLORD);
    private final AtomicInteger ids = new AtomicInteger(100);

    @BeforeEach void setup() throws Exception {
        when(client.execute(any(SendMessage.class))).thenAnswer(call -> {
            Message sent = new Message();
            sent.setMessageId(ids.incrementAndGet());
            return sent;
        });
    }

    @AfterEach void historyAndKeyboardsAreNeverModified() throws Exception {
        verify(client, never()).execute(any(EditMessageText.class));
        verify(client, never()).execute(any(EditMessageReplyMarkup.class));
        verify(client, never()).execute(any(DeleteMessage.class));
    }

    @ParameterizedTest @EnumSource(Role.class)
    void everyNavigationSendsNewMessageEvenFromOldMenu(Role role) throws Exception {
        user.setRole(role);
        initial();
        for (int source : List.of(101, 101)) {
            try (var ui = request("ACCOUNT_INFO", source)) {
                ui.sendMessageWithKeyboard(123L, "Tài khoản", TelegramKeyboardUtil.getRoleMenu(role));
            }
        }
        assertEquals(3, sentMessages().size());
        assertEquals("Tài khoản", sentMessages().get(2).getText());
        verifyNoInteractions(events);
    }

    @Test void noticeIsNewMessageAndCallbackOnlyAcknowledgesReceipt() throws Exception {
        initial();
        try (var ui = current("ACCOUNT_INFO")) { ui.sendNotice(123L, "Không có quyền."); }
        verify(events).sendMessage(123L, "Không có quyền.");
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(client).execute(answer.capture());
        assertNull(answer.getValue().getText());
        assertFalse(Boolean.TRUE.equals(answer.getValue().getShowAlert()));
    }

    @Test void receiptAndMenuAreSeparateNewMessages() throws Exception {
        initial();
        try (var ui = current("CONFIRM_RECORD_PAYMENT")) {
            ui.returnMenu("/invoice");
            ui.sendResultAndMenu(123L, "Đã ghi nhận thanh toán", TelegramKeyboardUtil.getLandlordInvoiceManagementMenu());
        }
        verify(events).sendMessage(123L, "Đã ghi nhận thanh toán");
        assertEquals("📌 Chọn thao tác tiếp theo:", sentMessages().get(1).getText());
    }

    @Test void newSessionKeepsOldKeyboardUsable() throws Exception {
        initial();
        try (var ui = request("/menu", null)) {
            ui.newSession();
            ui.sendMessageWithKeyboard(123L, "Menu mới", TelegramKeyboardUtil.getLandlordMenu());
        }
        try (var ui = request("ACCOUNT_INFO", 101)) {
            ui.sendMessageWithKeyboard(123L, "Tài khoản", TelegramKeyboardUtil.getLandlordMenu());
        }
        assertEquals(3, sentMessages().size());
    }

    @Test void promptsLeavePreviousKeyboardUntouched() {
        initial();
        try (var ui = current("ROOM_ADD")) {
            ui.returnMenu("/room");
            ui.sendPrompt(123L, "Nhập số phòng:");
        }
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(events).sendMessageWithKeyboard(eq(123L), eq("Nhập số phòng:"), keyboard.capture());
        assertEquals("/cancel", keyboard.getValue().getKeyboard().get(0).get(0).getCallbackData());
        assertEquals(1, ids.get() - 100); // The prompt does not replace the previous navigation screen.
        try (var ui = request("/cancel", null)) { assertEquals("LANDLORD_ROOMS", ui.pendingMenu()); }
    }

    @Test void promptKeepsInputTextWithoutAddingCommandInstructions() {
        try (var ui = request("ROOM_ADD", null)) {
            ui.sendPrompt(123L, "Nhập số phòng.");
        }
        verify(events).sendMessageWithKeyboard(eq(123L),
                eq("Nhập số phòng."), any());
    }

    @Test void selectionCancelRemainsOnEveryPageWithoutDuplicateButtons() throws Exception {
        states.setState(123L, new com.boardinghousebot.state.UserConversationState(
                com.boardinghousebot.state.UserConversationState.Step.CREATE_INVOICE_ROOM_SELECT));
        List<org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 45; i++) {
            rows.add(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                    TelegramKeyboardUtil.createButton("Phòng " + i, "INVOICE_SELECT_ROOM_" + i)));
        }
        rows.add(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                TelegramKeyboardUtil.createButton("❌ Hủy", "CANCEL_CREATE_INVOICE")));
        try (var ui = request("INVOICE_ADD", null)) {
            ui.sendMessageWithKeyboard(123L, "Chọn phòng", new InlineKeyboardMarkup(rows));
        }
        try (var ui = current("UI_PAGE_1")) { assertTrue(ui.page("UI_PAGE_1")); }
        for (SendMessage sent : sentMessages()) {
            assertFalse(sent.getText().contains("/cancel"));
            var buttons = ((InlineKeyboardMarkup) sent.getReplyMarkup()).getKeyboard().stream().flatMap(List::stream).toList();
            assertEquals(1, buttons.stream().filter(button -> "CANCEL_CREATE_INVOICE".equals(button.getCallbackData())).count());
            assertTrue(buttons.stream().noneMatch(button -> "/cancel".equals(button.getCallbackData())));
        }
    }

    @Test void selectionWithoutCancelGetsButton() throws Exception {
        states.setState(123L, new com.boardinghousebot.state.UserConversationState(
                com.boardinghousebot.state.UserConversationState.Step.RECORD_PAYMENT_METHOD));
        try (var ui = request("PAYMENT_METHOD", null)) {
            ui.sendMessageWithKeyboard(123L, "Chọn phương thức", new InlineKeyboardMarkup(List.of()));
        }
        var keyboard = (InlineKeyboardMarkup) sentMessages().get(0).getReplyMarkup();
        assertEquals(1, keyboard.getKeyboard().stream().flatMap(List::stream)
                .filter(button -> "/cancel".equals(button.getCallbackData())).count());
    }

    @Test void wizardStepsKeepChoicesWithoutBackOrMainMenu() throws Exception {
        initial();
        try (var ui = current("LANDLORD_CONTRACTS")) {
            ui.sendMessageWithKeyboard(123L, "Hợp đồng", TelegramKeyboardUtil.getLandlordContractManagementMenu());
        }
        states.setState(123L, new com.boardinghousebot.state.UserConversationState(
                com.boardinghousebot.state.UserConversationState.Step.UPDATE_CONTRACT_CONFIRM));
        var keyboard = new InlineKeyboardMarkup(List.of(
                new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("✅ Xác nhận", "CONFIRM_UPDATE_CONTRACT"),
                        TelegramKeyboardUtil.createButton("🔙 Quay lại trang", "UI_BACK")),
                new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        TelegramKeyboardUtil.createButton("Menu chính", "MAIN_MENU"))));
        try (var ui = current("CONTRACT_UPDATE_1")) {
            ui.returnMenu("/contract");
            ui.sendMessageWithKeyboard(123L, "Xác nhận thay đổi", keyboard);
            assertEquals("LANDLORD_CONTRACTS", ui.pendingMenu());
        }
        var controls = ((InlineKeyboardMarkup) sentMessages().get(2).getReplyMarkup()).getKeyboard()
                .stream().flatMap(List::stream).map(button -> button.getCallbackData()).toList();
        assertEquals(List.of("CONFIRM_UPDATE_CONTRACT", "/cancel"), controls);
        // Sanitizing a step must not alter a reusable keyboard or an older message.
        assertEquals(2, keyboard.getKeyboard().get(0).size());
    }

    @Test void invalidWizardInputOffersCancelWithoutChangingStep() {
        var step = com.boardinghousebot.state.UserConversationState.Step.UPDATE_CONTRACT_END_DATE;
        states.setState(123L, new com.boardinghousebot.state.UserConversationState(step));
        try (var ui = request("invalid date", null)) {
            ui.returnMenu("/contract");
            ui.sendNotice(123L, "Ngày không hợp lệ.");
            assertEquals("LANDLORD_CONTRACTS", ui.pendingMenu());
        }
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(events).sendMessageWithKeyboard(eq(123L), eq("Ngày không hợp lệ."), keyboard.capture());
        assertEquals(List.of("/cancel"), keyboard.getValue().getKeyboard().stream().flatMap(List::stream)
                .map(button -> button.getCallbackData()).toList());
        assertEquals(step, states.getState(123L).getStep());
    }

    @Test void deliveryFailurePreservesPreviousNavigation() throws Exception {
        initial();
        when(client.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("timeout"));
        try (var ui = current("ACCOUNT_INFO")) {
            ui.sendMessageWithKeyboard(123L, "Tài khoản", TelegramKeyboardUtil.getLandlordMenu());
        }
        verify(events).sendMessage(eq(123L), contains("Không thể gửi màn hình"));
        try (var ui = request("ACCOUNT_INFO", 101)) { assertEquals("/start", ui.screenAction()); }
    }

    @Test void markdownFailureRetriesNewPlainTextMessage() throws Exception {
        initial();
        when(client.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("can't parse entities")).thenReturn(new Message());
        try (var ui = current("ACCOUNT_INFO")) {
            ui.sendMessageWithKeyboard(123L, "Tên _không đóng", TelegramKeyboardUtil.getLandlordMenu());
        }
        List<SendMessage> sent = sentMessages();
        assertEquals(3, sent.size());
        assertNull(sent.get(2).getParseMode());
        assertEquals("Tên _không đóng", sent.get(2).getText());
    }

    @Test void backFromOldDetailKeepsItsOwnListAndFilterAfterNewMenu() {
        initial();
        try (var ui = current("INVOICE_LIST_UNPAID")) {
            states.getState(123L).getData().put("invoiceFilterStatus", "UNPAID");
            ui.sendMessageWithKeyboard(123L, "Chưa trả", new InlineKeyboardMarkup(List.of(
                    new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                            TelegramKeyboardUtil.createButton("Hóa đơn #1", "INVOICE_DETAIL_1")),
                    new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                            TelegramKeyboardUtil.createButton("⬅️ Danh sách hóa đơn", "INVOICE_LIST_LATEST")),
                    new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                            TelegramKeyboardUtil.createButton("⬅️ Menu chính", "MAIN_MENU")))));
        }
        try (var ui = current("INVOICE_DETAIL_1")) {
            ui.sendMessageWithKeyboard(123L, "Chi tiết", TelegramKeyboardUtil.getInvoiceDetailKeyboard(1L, false));
        }
        int detailId = ids.get();
        try (var ui = request("/menu", null)) {
            ui.newSession();
            ui.sendMessageWithKeyboard(123L, "Menu mới", TelegramKeyboardUtil.getLandlordMenu());
        }
        try (var ui = request("UI_BACK", detailId)) {
            var destination = ui.back();
            assertEquals("INVOICE_LIST_UNPAID", destination.action());
            assertEquals("UNPAID", destination.filters().get("invoiceFilterStatus"));
        }
    }

    @Test void oldPageUsesSourceScreenAndSendsNewPage() throws Exception {
        initial();
        try (var ui = current("ROOM_LIST_AVAILABLE")) {
            ui.sendMessageWithKeyboard(123L, "Phòng 101\n".repeat(900), TelegramKeyboardUtil.getRoomListKeyboard(List.of()));
        }
        int listId = ids.get();
        try (var ui = current("ACCOUNT_INFO")) {
            ui.sendMessageWithKeyboard(123L, "Tài khoản", TelegramKeyboardUtil.getLandlordMenu());
        }
        try (var ui = request("UI_PAGE_1", listId)) { assertTrue(ui.page("UI_PAGE_1")); }
        List<SendMessage> sent = sentMessages();
        assertTrue(sent.stream().allMatch(message -> message.getText().length() <= 4096));
        assertTrue(sent.get(3).getText().contains("Phòng 101"));
        assertTrue(sent.get(3).getText().contains("Trang 2/"));
    }

    @Test void changedRoleCannotPageCachedScreen() {
        initial();
        user.setRole(Role.MEMBER);
        try (var ui = request("UI_PAGE_0", 101)) { assertFalse(ui.page("UI_PAGE_0")); }
    }

    @Test void unchangedFunctionsStillHaveNoRedundantBack() throws Exception {
        user.setRole(Role.ADMIN);
        initial();
        for (String action : List.of("ADMIN_USERS", "ADMIN_LIST_USERS", "/search_users An")) {
            try (var ui = current(action)) {
                ui.sendMessageWithKeyboard(123L, action, TelegramKeyboardUtil.getAdminUserManagementMenu());
            }
        }
        for (SendMessage sent : sentMessages().subList(1, 4)) {
            var callbacks = ((InlineKeyboardMarkup) sent.getReplyMarkup()).getKeyboard().stream().flatMap(List::stream)
                    .map(button -> button.getCallbackData()).toList();
            assertFalse(callbacks.contains("UI_BACK"));
            assertTrue(callbacks.contains("MAIN_MENU"));
        }
    }

    private List<SendMessage> sentMessages() throws Exception {
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, atLeastOnce()).execute(sent.capture());
        return sent.getAllValues();
    }
    private void initial() {
        try (var ui = request("/start", null)) {
            ui.newSession();
            ui.sendMessageWithKeyboard(123L, "Menu chính", TelegramKeyboardUtil.getRoleMenu(user.getRole()));
        }
    }
    private BotUiSessionManager.Request current(String action) { return request(action, ids.get()); }
    private BotUiSessionManager.Request request(String action, Integer messageId) {
        Update update = new Update();
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        if (messageId == null) {
            message.setText(action);
            update.setMessage(message);
        } else {
            message.setMessageId(messageId);
            CallbackQuery callback = new CallbackQuery();
            callback.setId("query_" + action);
            callback.setData(action);
            callback.setMessage(message);
            update.setCallbackQuery(callback);
        }
        var ui = manager.open(update, user, events, states);
        assertTrue(ui.acceptsCallback());
        return ui;
    }
}
