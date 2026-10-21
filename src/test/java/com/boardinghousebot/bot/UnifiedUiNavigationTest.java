package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.bot.handler.RoomCommandHandler;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.RoomStayService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedUiNavigationTest {
    private final UserService users = mock(UserService.class);
    private final RoomStayService stays = mock(RoomStayService.class);
    private final RoomService rooms = mock(RoomService.class);
    private final TelegramClient client = mock(TelegramClient.class);
    private final ConversationStateService states = new ConversationStateService();
    private final CommandHandler root = mock(CommandHandler.class);
    private final AtomicInteger messageIds = new AtomicInteger(76);
    private BoardingHouseTelegramBot bot;

    @BeforeEach
    void setUp() throws Exception {
        when(root.getCommand()).thenReturn("/start");
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(123L, "owner", "Owner", Role.LANDLORD));
        when(stays.allowsMemberAction(any(), anyString(), anyString(), any())).thenReturn(true);
        doAnswer(call -> {
            BotMessageSender sender = call.getArgument(2);
            sender.sendMessageWithKeyboard(123L, "Menu chính", TelegramKeyboardUtil.getLandlordMenu());
            return null;
        }).when(root).handle(any(), any(), any());
        Room room = new Room("201", new BigDecimal("5000000"), 5, "Phòng tầng 2");
        room.setId(2L);
        when(rooms.getAllRooms()).thenReturn(List.of(room));
        when(rooms.getRoomById(2L)).thenReturn(room);
        when(rooms.getRoomTenants(2L)).thenReturn(List.of());
        when(client.execute(any(SendMessage.class))).thenAnswer(call -> {
            Message message = new Message();
            message.setMessageId(messageIds.incrementAndGet());
            return message;
        });
        CommandHandler menu = mock(CommandHandler.class);
        when(menu.getCommand()).thenReturn("/menu");
        doAnswer(call -> {
            BotMessageSender sender = call.getArgument(2);
            sender.sendMessageWithKeyboard(123L, "Menu chính", TelegramKeyboardUtil.getLandlordMenu());
            return null;
        }).when(menu).handle(any(), any(), any());
        bot = new BoardingHouseTelegramBot(new TelegramConfig(), users, states, stays,
                List.of(root, menu, new RoomCommandHandler(rooms, states)));
        ReflectionTestUtils.setField(bot, "telegramClient", client);
        bot.consume(text("/start"));
    }

    @Test
    void completeRoomNavigationSendsNewScreensAndBackRereadsCorrectFloor() throws Exception {
        for (String callback : List.of("LANDLORD_ROOMS", "ROOM_LIST_ALL", "ROOM_FLOOR_2_0", "ROOM_DETAIL_2_F_2_0", "UI_BACK")) {
            bot.consume(callback(callback));
        }
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(6)).execute(sent.capture());
        assertTrue(sent.getValue().getText().contains("Tầng 2 (1 phòng)"));
        verify(client, never()).execute(any(EditMessageText.class));
        verify(rooms, times(4)).getAllRooms();
    }

    @Test
    void cancelTextIsRoutedToActiveWizardAndReturnsRoomManagement() throws Exception {
        bot.consume(callback("LANDLORD_ROOMS"));
        bot.consume(callback("ROOM_ADD"));
        assertEquals(UserConversationState.Step.CREATE_ROOM_NUMBER, states.getState(123L).getStep());
        bot.consume(text("/cancel"));
        assertEquals(UserConversationState.Step.NONE, states.getState(123L).getStep());
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(4)).execute(sent.capture());
        assertTrue(sent.getValue().getText().contains("Đã hủy"));
        assertTrue(((org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup) sent.getValue().getReplyMarkup()).getKeyboard().stream().flatMap(List::stream)
                .anyMatch(button -> "ROOM_ADD".equals(button.getCallbackData())));
        verify(rooms, never()).createRoom(anyLong(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void oldFilteredMessageKeepsItsReturnContextAndBackReadsFreshOccupancy() throws Exception {
        bot.consume(callback("LANDLORD_ROOMS"));
        bot.consume(callback("ROOM_LIST_ALL"));
        bot.consume(callback("ROOM_FLOOR_2_0"));
        bot.consume(callback("ROOM_FLOOR_2_0_AVAILABLE"));
        int availableMessage = messageIds.get();
        bot.consume(callback("ROOM_FLOOR_2_0_FULL"));
        bot.consume(callback("ROOM_DETAIL_2_F_2_0_AVAILABLE", availableMessage));
        when(rooms.getCurrentOccupantCount(2L)).thenReturn(5);
        bot.consume(callback("UI_BACK"));
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(8)).execute(sent.capture());
        assertTrue(sent.getValue().getText().contains("Không có phòng phù hợp"));
        var keyboard = (org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup) sent.getValue().getReplyMarkup();
        assertTrue(keyboard.getKeyboard().stream().flatMap(List::stream)
                .anyMatch(b -> "ROOM_FLOOR_2_0_AVAILABLE".equals(b.getCallbackData()) && b.getText().startsWith("✓")));
        for (SendMessage message : sent.getAllValues().subList(1, sent.getAllValues().size())) {
            var controls = (org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup) message.getReplyMarkup();
            assertTrue(controls.getKeyboard().stream().flatMap(List::stream).anyMatch(b -> "MAIN_MENU".equals(b.getCallbackData())));
        }
        verify(client, never()).execute(any(EditMessageText.class));
        verify(client, never()).execute(any(org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup.class));
        verify(client, never()).execute(any(org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage.class));
    }

    @Test
    void everyRoomInputAndConfirmationOffersCancelWithoutCommandHint() throws Exception {
        bot.consume(callback("ROOM_ADD"));
        for (String input : List.of("101", "3500000", "4", "/skip")) {
            bot.consume(text(input));
        }
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(6)).execute(sent.capture());
        for (SendMessage prompt : sent.getAllValues().subList(1, 6)) {
            assertFalse(prompt.getText().contains("/cancel"));
            var keyboard = (org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup) prompt.getReplyMarkup();
            assertEquals(1, keyboard.getKeyboard().stream().flatMap(List::stream)
                    .filter(button -> "/cancel".equals(button.getCallbackData())
                            || "CANCEL_CREATE_ROOM".equals(button.getCallbackData())).count());
            assertTrue(keyboard.getKeyboard().stream().flatMap(List::stream)
                    .noneMatch(button -> "MAIN_MENU".equals(button.getCallbackData())
                            || "UI_BACK".equals(button.getCallbackData()) || button.getText().startsWith("🔙")));
        }
        bot.consume(callback("CANCEL_CREATE_ROOM"));
        assertEquals(UserConversationState.Step.NONE, states.getState(123L).getStep());
        bot.consume(callback("CONFIRM_CREATE_ROOM"));
        verify(rooms, never()).createRoom(anyLong(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void oldRoomManagementButtonStillStartsAuthorizedWizard() {
        bot.consume(callback("LANDLORD_ROOMS"));
        int managementId = messageIds.get();
        bot.consume(callback("ROOM_LIST_ALL"));
        bot.consume(callback("ROOM_ADD", managementId));
        assertEquals(UserConversationState.Step.CREATE_ROOM_NUMBER, states.getState(123L).getStep());
    }

    private Update callback(String action) {
        return callback(action, messageIds.get());
    }

    private Update callback(String action, int messageId) {
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        message.setMessageId(messageId);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("callback_" + action);
        callback.setData(action);
        callback.setMessage(message);
        callback.setFrom(new org.telegram.telegrambots.meta.api.objects.User(123L, "Owner", false));
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    @Test
    void changedAdminConfigurationCannotPageCachedAuditData() throws Exception {
        User admin = new User(123L, "admin", "Admin", Role.ADMIN);
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(admin);
        doAnswer(call -> {
            BotMessageSender sender = call.getArgument(2);
            sender.sendMessageWithKeyboard(123L, "Admin", TelegramKeyboardUtil.getAdminMenu());
            return null;
        }).when(root).handle(any(), any(), any());
        CommandHandler menu = mock(CommandHandler.class);
        when(menu.getCommand()).thenReturn("/menu");
        doAnswer(call -> {
            BotMessageSender sender = call.getArgument(2);
            sender.sendMessageWithKeyboard(123L, "Nhật ký quản trị\n".repeat(600), TelegramKeyboardUtil.getAdminMenu());
            return null;
        }).when(menu).handle(any(), any(), any());
        TelegramConfig config = new TelegramConfig();
        config.setAdminId("123");
        BoardingHouseTelegramBot adminBot = new BoardingHouseTelegramBot(config, users, states, stays, List.of(root, menu));
        ReflectionTestUtils.setField(adminBot, "telegramClient", client);
        adminBot.consume(text("/start"));
        adminBot.consume(callback("ADMIN_AUDIT"));
        config.setAdminId("456");
        clearInvocations(client);
        adminBot.consume(callback("UI_PAGE_1"));
        verify(client, never()).execute(any(EditMessageText.class));
        ArgumentCaptor<AnswerCallbackQuery> answer = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(client).execute(answer.capture());
        assertNull(answer.getValue().getText());
        ArgumentCaptor<SendMessage> denied = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(denied.capture());
        assertTrue(denied.getValue().getText().contains("không có quyền"));
    }

    @Test
    void backFromOldRoomDetailKeepsFloorAfterReturningToMainMenu() throws Exception {
        for (String action : List.of("LANDLORD_ROOMS", "ROOM_LIST_ALL", "ROOM_FLOOR_2_0", "ROOM_DETAIL_2_F_2_0")) {
            bot.consume(callback(action));
        }
        int detailId = messageIds.get();
        bot.consume(callback("MAIN_MENU"));
        bot.consume(callback("UI_BACK", detailId));
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(7)).execute(sent.capture());
        assertTrue(sent.getValue().getText().contains("Tầng 2 (1 phòng)"));
    }

    private Update text(String text) {
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        message.setText(text);
        message.setFrom(new org.telegram.telegrambots.meta.api.objects.User(123L, "Owner", false));
        Update update = new Update();
        update.setMessage(message);
        return update;
    }
}
