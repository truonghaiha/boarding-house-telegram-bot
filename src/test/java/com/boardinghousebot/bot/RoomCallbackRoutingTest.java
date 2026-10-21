package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomStayService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomCallbackRoutingTest {
    private final UserService users = mock(UserService.class);
    private final RoomStayService stays = mock(RoomStayService.class);
    private final CommandHandler rooms = mock(CommandHandler.class);
    private final CommandHandler tenants = mock(CommandHandler.class);

    private BoardingHouseTelegramBot bot() {
        when(rooms.getCommand()).thenReturn("/room");
        when(tenants.getCommand()).thenReturn("/tenant");
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(123L, "landlord", "Landlord", Role.LANDLORD));
        when(stays.allowsMemberAction(any(), anyString(), anyString(), any())).thenReturn(true);
        return new BoardingHouseTelegramBot(new TelegramConfig(), users, new ConversationStateService(), stays,
                List.of(rooms, tenants));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_LIST_ALL", "ROOM_FLOORS_1", "ROOM_FLOOR_2_0", "ROOM_DETAIL_2_F_2_0", "ROOM_DETAIL_2", "ROOM_DELETE_2",
            "ROOM_REMOVE_TENANTS_2", "ROOM_REMOVE_SELECT_2_123", "ROOM_REMOVE_CONFIRM_2_123", "CANCEL_ROOM_REMOVE_TENANT"})
    void navigationAndOldRoomCallbacksReachRoomHandler(String callback) {
        BoardingHouseTelegramBot bot = bot();
        Update update = update(callback);
        bot.consume(update);
        verify(rooms).handle(eq(update), any(), any(com.boardinghousebot.bot.ui.BotUiSessionManager.Request.class));
        verify(tenants, never()).handle(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_TENANTS_2", "ROOM_ASSIGN_2", "ROOM_MOVE_2"})
    void roomTenantActionsReachExistingTenantHandler(String callback) {
        BoardingHouseTelegramBot bot = bot();
        Update update = update(callback);
        bot.consume(update);
        verify(tenants).handle(eq(update), any(), any(com.boardinghousebot.bot.ui.BotUiSessionManager.Request.class));
        verify(rooms, never()).handle(any(), any(), any());
    }

    @Test
    void compatibilityScreenApiSendsNewMessageEvenWithMessageId() throws Exception {
        BoardingHouseTelegramBot bot = bot();
        TelegramClient client = mock(TelegramClient.class);
        ReflectionTestUtils.setField(bot, "telegramClient", client);
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(List.of());
        bot.sendOrEditMessageWithKeyboard(123L, 77, "🏢 DANH SÁCH TẦNG", keyboard);
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(sent.capture());
        assertEquals("123", sent.getValue().getChatId());
        assertSame(keyboard, sent.getValue().getReplyMarkup());
        assertEquals("🏢 DANH SÁCH TẦNG", sent.getValue().getText());
        verify(client, never()).execute(any(EditMessageText.class));
    }

    private Update update(String data) {
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        message.setMessageId(77);
        CallbackQuery callback = new CallbackQuery();
        callback.setMessage(message);
        callback.setFrom(new org.telegram.telegrambots.meta.api.objects.User(123L, "Landlord", false));
        callback.setData(data);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }
}
