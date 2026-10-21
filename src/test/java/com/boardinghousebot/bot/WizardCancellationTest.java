package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.bot.handler.*;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.state.UserConversationState.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WizardCancellationTest {
    private final UserService users = mock(UserService.class);
    private final RoomService rooms = mock(RoomService.class);
    private final ContractService contracts = mock(ContractService.class);
    private final MeterReadingService meters = mock(MeterReadingService.class);
    private final InvoiceService invoices = mock(InvoiceService.class);
    private final PaymentService payments = mock(PaymentService.class);
    private final MaintenanceService maintenance = mock(MaintenanceService.class);
    private final ContractRepository contractRepository = mock(ContractRepository.class);
    private final RoomStayService stays = mock(RoomStayService.class);
    private final org.telegram.telegrambots.meta.generics.TelegramClient client =
            mock(org.telegram.telegrambots.meta.generics.TelegramClient.class);
    private final ConversationStateService states = new ConversationStateService();
    private BoardingHouseTelegramBot bot;

    @BeforeEach void setup() throws Exception {
        TelegramConfig config = new TelegramConfig();
        config.setAdminId("123");
        when(stays.allowsMemberAction(any(), anyString(), anyString(), any())).thenReturn(true);
        when(client.execute(any(SendMessage.class))).thenReturn(new Message());
        List<CommandHandler> handlers = List.of(
                new RoomCommandHandler(rooms, states),
                new TenantCommandHandler(rooms, users, states, contractRepository),
                new ContractCommandHandler(contracts, users, states),
                new MeterCommandHandler(meters, rooms, users, states),
                new InvoiceCommandHandler(invoices, payments, states),
                new MaintenanceCommandHandler(maintenance, states),
                new MenuHandler(users, mock(AuditLogService.class), mock(StatisticsService.class), states,
                        mock(StartHandler.class), new AuthorizationService(config)));
        bot = new BoardingHouseTelegramBot(config, users, states, stays, handlers);
        ReflectionTestUtils.setField(bot, "telegramClient", client);
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void buttonAndTextCancelEveryWizardStepWithoutSaving(Step step) throws Exception {
        Role role = step.name().startsWith("ADMIN_") ? Role.ADMIN
                : step.name().startsWith("CREATE_MAINTENANCE") ? Role.MEMBER : Role.LANDLORD;
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(123L, "user", "User", role));
        boolean roomRemoval = step.name().startsWith("REMOVE_ROOM_TENANT") || step.name().startsWith("EDIT_ROOM");
        if (roomRemoval) {
            var room = new com.boardinghousebot.domain.room.Room("101", java.math.BigDecimal.TEN, 2, "Room");
            room.setId(1L);
            when(rooms.getRoomById(1L)).thenReturn(room);
            when(rooms.getRoomTenants(1L)).thenReturn(List.of());
        }
        for (boolean button : List.of(true, false)) {
            clearInvocations(client);
            UserConversationState state = new UserConversationState(step);
            state.setRoomNumber("101");
            state.setTargetRoomId(1L);
            state.getData().put("draft", "unsaved");
            states.setState(123L, state);

            bot.consume(cancelUpdate(button));

            assertEquals(Step.NONE, states.getState(123L).getStep(), step.name());
            assertTrue(states.getState(123L).getData().isEmpty());
            assertNull(states.getState(123L).getRoomNumber());
            assertNull(states.getState(123L).getTargetRoomId());
            ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
            if (roomRemoval) verify(client, times(2)).execute(sent.capture());
            else verify(client).execute(sent.capture());
            var keyboard = (InlineKeyboardMarkup) sent.getValue().getReplyMarkup();
            assertTrue(keyboard.getKeyboard().stream().flatMap(List::stream)
                    .anyMatch(key -> (roomRemoval ? "ROOM_REMOVE_TENANTS_1" : menuButton(step)).equals(key.getCallbackData())), step.name());
            assertTrue(keyboard.getKeyboard().stream().flatMap(List::stream)
                    .noneMatch(key -> "/cancel".equals(key.getCallbackData())));
            if (button) verify(client).execute(any(AnswerCallbackQuery.class));
            if (step == Step.ASSIGN_TENANT_TELEGRAM_ID) {
                assertTrue(sent.getValue().getText().contains("thêm người thuê"));
            }
            if (roomRemoval) {
                verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong());
                verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
            } else verifyNoInteractions(rooms);
            verifyNoInteractions(contracts, meters, invoices, payments, maintenance, contractRepository);
        }
        verify(users, never()).searchUsersAsAdmin(any(), anyString());
    }

    @Test void paymentSelectionKeepsWizardOnInvalidTextAndCancelsToInvoiceMenu() throws Exception {
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(123L, "owner", "Owner", Role.LANDLORD));
        var room = new com.boardinghousebot.domain.room.Room("101", java.math.BigDecimal.TEN, 4, "Room");
        var invoice = new com.boardinghousebot.domain.invoice.Invoice(room, null, null, 10, 2026,
                java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.TEN, java.time.LocalDate.of(2026, 10, 20));
        invoice.setId(1L);
        when(invoices.getAllInvoices()).thenReturn(List.of(invoice));
        bot.consume(inputUpdate("INVOICE_PAYMENT_SELECT", true));
        assertEquals(Step.RECORD_PAYMENT_INVOICE_SELECT, states.getState(123L).getStep());
        bot.consume(inputUpdate("invalid", false));
        assertEquals(Step.RECORD_PAYMENT_INVOICE_SELECT, states.getState(123L).getStep());
        bot.consume(cancelUpdate(true));
        assertEquals(Step.NONE, states.getState(123L).getStep());
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(3)).execute(sent.capture());
        var error = sent.getAllValues().get(1);
        var errorKeyboard = (InlineKeyboardMarkup) error.getReplyMarkup();
        assertEquals(List.of("/cancel"), errorKeyboard.getKeyboard().stream().flatMap(List::stream)
                .map(key -> key.getCallbackData()).toList());
        assertTrue(sent.getValue().getText().contains("hủy thao tác ghi nhận thanh toán"));
        verifyNoInteractions(payments);
    }

    @Test void invalidContractEditCanCancelFromErrorMessageWithoutSaving() throws Exception {
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(123L, "owner", "Owner", Role.LANDLORD));
        var state = new UserConversationState(Step.UPDATE_CONTRACT_END_DATE);
        state.getData().put("contractId", 1L);
        state.getData().put("startDate", java.time.LocalDate.of(2026, 10, 14));
        states.setState(123L, state);
        bot.consume(inputUpdate("13/10/2026", false));
        assertEquals(Step.UPDATE_CONTRACT_END_DATE, states.getState(123L).getStep());
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(sent.capture());
        assertTrue(sent.getValue().getText().contains("không hợp lệ"));
        var buttons = ((InlineKeyboardMarkup) sent.getValue().getReplyMarkup()).getKeyboard().stream()
                .flatMap(List::stream).toList();
        assertEquals(List.of("/cancel"), buttons.stream().map(key -> key.getCallbackData()).toList());
        bot.consume(inputUpdate(buttons.get(0).getCallbackData(), true));
        assertEquals(Step.NONE, states.getState(123L).getStep());
        assertTrue(states.getState(123L).getData().isEmpty());
        verifyNoInteractions(contracts);
        verify(client, times(2)).execute(sent.capture());
        assertTrue(((InlineKeyboardMarkup) sent.getValue().getReplyMarkup()).getKeyboard().stream()
                .flatMap(List::stream).anyMatch(key -> "CONTRACT_ADD".equals(key.getCallbackData())));
    }

    @Test void cancelButtonOnTenantAssignmentIdStepReturnsToItsMenu() throws Exception {
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(123L, "owner", "Owner", Role.LANDLORD));
        bot.consume(inputUpdate("TENANT_ADD_INPUT", true));
        assertEquals(Step.ASSIGN_TENANT_TELEGRAM_ID, states.getState(123L).getStep());
        bot.consume(cancelUpdate(true));
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(2)).execute(sent.capture());
        var prompt = sent.getAllValues().get(0);
        assertFalse(prompt.getText().contains("/cancel"));
        assertEquals("/cancel", ((InlineKeyboardMarkup) prompt.getReplyMarkup()).getKeyboard().get(0).get(0).getCallbackData());
        assertTrue(sent.getValue().getText().contains("hủy thao tác thêm người thuê"));
        verifyNoInteractions(rooms);
    }

    private String menuButton(Step step) {
        String name = step.name();
        if (name.startsWith("ADMIN_")) return "ADMIN_LIST_USERS";
        if (name.startsWith("CREATE_ROOM")) return "ROOM_ADD";
        if (name.startsWith("ASSIGN_TENANT") || name.startsWith("MOVE_TENANT") || name.startsWith("EDIT_TENANT") || name.startsWith("REMOVE_TENANT")) return "TENANT_ADD_INPUT";
        if (name.contains("CONTRACT")) return "CONTRACT_ADD";
        if (name.contains("METER") || name.startsWith("UPDATE_UTILITY")) return "METER_ADD";
        if (name.contains("INVOICE") || name.startsWith("RECORD_PAYMENT")) return "INVOICE_ADD";
        return name.startsWith("CREATE_MAINTENANCE") ? "MEMBER_MAINTENANCE" : "MAINTENANCE_LIST_ALL";
    }

    private Update cancelUpdate(boolean button) {
        return inputUpdate("/cancel", button);
    }

    private Update inputUpdate(String action, boolean button) {
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        var from = new org.telegram.telegrambots.meta.api.objects.User(123L, "User", false);
        Update update = new Update();
        if (button) {
            message.setMessageId(10);
            CallbackQuery callback = new CallbackQuery();
            callback.setId("cancel");
            callback.setData(action);
            callback.setFrom(from);
            callback.setMessage(message);
            update.setCallbackQuery(callback);
        } else {
            message.setFrom(from);
            message.setText(action);
            update.setMessage(message);
        }
        return update;
    }
}
