package com.boardinghousebot.bot;

import com.boardinghousebot.bot.handler.*;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.maintenance.*;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.*;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.*;
import com.boardinghousebot.state.UserConversationState.Step;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptionalInputFlowTest {
    final UserService users = mock(UserService.class);
    final RoomService rooms = mock(RoomService.class);
    final InvoiceService invoices = mock(InvoiceService.class);
    final PaymentService payments = mock(PaymentService.class);
    final MaintenanceService maintenance = mock(MaintenanceService.class);
    final RoomStayService stays = mock(RoomStayService.class);
    final TelegramClient client = mock(TelegramClient.class);
    final ConversationStateService states = new ConversationStateService();
    final com.boardinghousebot.domain.user.User owner = new com.boardinghousebot.domain.user.User(99L, "owner", "Owner", Role.LANDLORD);
    final Room room = new Room("101", new BigDecimal("4000000"), 2, "");
    BoardingHouseTelegramBot bot;

    @BeforeEach void setup() throws Exception {
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(owner);
        when(stays.allowsMemberAction(any(), anyString(), anyString(), any())).thenReturn(true);
        when(client.execute(any(SendMessage.class))).thenReturn(new Message());
        bot = new BoardingHouseTelegramBot(new TelegramConfig(), users, states, stays, List.of(
                new RoomCommandHandler(rooms, states), new InvoiceCommandHandler(invoices, payments, states),
                new MaintenanceCommandHandler(maintenance, states)));
        ReflectionTestUtils.setField(bot, "telegramClient", client);
    }

    @Test void optionalRoomDescriptionOffersSkipAndCancelAndUsesExistingDefault() throws Exception {
        var state = new UserConversationState(Step.CREATE_ROOM_MAX);
        state.setRoomNumber("101"); state.setMonthlyRent(new BigDecimal("4000000")); states.setState(99L, state);
        text("2"); text("25"); text("3000000"); assertOptionalPrompt(Step.CREATE_ROOM_DESC);
        click("SKIP_INPUT_CREATE_ROOM_DESC");
        assertEquals(Step.CREATE_ROOM_CONFIRM, states.getState(99L).getStep());
        assertEquals("Chưa có mô tả", states.getState(99L).getDescription());
        assertTrue(last().getText().contains("Chưa có mô tả"));
        verifyNoInteractions(rooms);
        click("SKIP_INPUT_CREATE_ROOM_DESC"); // Repeated/old button must not change the confirmation step.
        assertEquals(Step.CREATE_ROOM_CONFIRM, states.getState(99L).getStep());
        assertTrue(last().getText().contains("Không thể bỏ qua"));
    }

    @Test void paymentGoesDirectlyToConfirmationWithoutOptionalFields() throws Exception {
        Invoice invoice = new Invoice(room, owner, null, 10, 2026, new BigDecimal("4000000"), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("4000000"), LocalDate.of(2026, 10, 15));
        when(invoices.getInvoiceById(1L)).thenReturn(invoice);
        var state = new UserConversationState(Step.RECORD_PAYMENT_METHOD);
        state.getData().put("invoiceId", 1L); state.getData().put("amount", new BigDecimal("1000000")); states.setState(99L, state);
        click("INVOICE_PAYMENT_INIT_1");
        assertEquals(Step.RECORD_PAYMENT_CONFIRM, states.getState(99L).getStep());
        assertFalse(states.getState(99L).getData().containsKey("note"));
        assertFalse(states.getState(99L).getData().containsKey("paymentMethod"));
        verifyNoInteractions(payments);
    }

    @Test void maintenanceStatusUpdatesImmediatelyWithoutNotePrompt() throws Exception {
        MaintenanceRequest request = new MaintenanceRequest(owner, room, "Vòi nước", "Hỏng");
        request.setId(1L); request.setStatus(MaintenanceStatus.RESOLVED);
        when(maintenance.updateMaintenanceStatus(99L, 1L, MaintenanceStatus.RESOLVED, "")).thenReturn(request);
        click("MAINTENANCE_UPDATE_RESOLVED_1");
        verify(maintenance).updateMaintenanceStatus(99L, 1L, MaintenanceStatus.RESOLVED, "");
        assertEquals(Step.NONE, states.getState(99L).getStep());
    }

    @ParameterizedTest
    @EnumSource(value = Step.class, names = {"CREATE_ROOM_DESC"}, mode = EnumSource.Mode.EXCLUDE)
    void requiredStepsCannotBeSkippedEvenWithForgedCallback(Step step) throws Exception {
        states.setState(99L, new UserConversationState(step));
        click("SKIP_INPUT_" + step.name());
        assertEquals(step, states.getState(99L).getStep());
        assertTrue(last().getText().contains("Không thể bỏ qua"));
        verifyNoInteractions(rooms, invoices, payments, maintenance);
    }

    @Test void skipForAnotherStepIsRejectedWithoutChangingDraft() throws Exception {
        states.setState(99L, new UserConversationState(Step.RECORD_PAYMENT_NOTE));
        click("SKIP_INPUT_CREATE_ROOM_DESC");
        assertEquals(Step.RECORD_PAYMENT_NOTE, states.getState(99L).getStep());
        verifyNoInteractions(rooms, invoices, payments, maintenance);
    }

    @Test void changedRoleCannotSkipLandlordOnlyInput() {
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new com.boardinghousebot.domain.user.User(99L, "member", "Member", Role.MEMBER));
        states.setState(99L, new UserConversationState(Step.UPDATE_MAINTENANCE_NOTE));
        click("SKIP_INPUT_UPDATE_MAINTENANCE_NOTE");
        verifyNoInteractions(maintenance);
    }

    @Test void optionalPromptCancelStillDiscardsDraft() {
        var state = new UserConversationState(Step.CREATE_ROOM_MAX);
        state.setRoomNumber("101"); state.setMonthlyRent(new BigDecimal("4000000")); states.setState(99L, state);
        text("2"); click("/cancel");
        assertEquals(Step.NONE, states.getState(99L).getStep()); verifyNoInteractions(rooms);
    }

    void assertOptionalPrompt(Step step) throws Exception {
        SendMessage prompt = last();
        assertTrue(prompt.getText().contains("có thể bỏ qua"));
        assertFalse(prompt.getText().contains("/skip")); assertFalse(prompt.getText().contains("/cancel"));
        assertFalse(prompt.getText().contains("gửi '-'"));
        var buttons = ((InlineKeyboardMarkup) prompt.getReplyMarkup()).getKeyboard().stream().flatMap(List::stream).toList();
        assertEquals(List.of("SKIP_INPUT_" + step.name(), "/cancel"), buttons.stream().map(b -> b.getCallbackData()).toList());
    }
    SendMessage last() throws Exception { var captor = ArgumentCaptor.forClass(SendMessage.class); verify(client, atLeastOnce()).execute(captor.capture()); return captor.getValue(); }
    void text(String text) { bot.consume(update(text, false)); }
    void click(String action) { bot.consume(update(action, true)); }
    Update update(String value, boolean callback) {
        Message message = new Message(); message.setChat(new Chat(99L, "private"));
        var actor = new org.telegram.telegrambots.meta.api.objects.User(99L, "Owner", false);
        Update update = new Update();
        if (callback) { CallbackQuery query = new CallbackQuery(); query.setData(value); query.setMessage(message); query.setFrom(actor); query.setId("test"); update.setCallbackQuery(query); }
        else { message.setText(value); message.setFrom(actor); update.setMessage(message); }
        return update;
    }
}
