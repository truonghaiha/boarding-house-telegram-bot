package com.boardinghousebot.bot;

import com.boardinghousebot.bot.handler.MenuHandler;
import com.boardinghousebot.bot.handler.StartHandler;
import com.boardinghousebot.config.BoardingHouseContactConfig;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MenuCommandSessionTest {
    private final TelegramClient client = mock(TelegramClient.class);
    private final UserService users = mock(UserService.class);
    private final RoomStayService stays = mock(RoomStayService.class);
    private final ConversationStateService states = new ConversationStateService();
    private final AtomicInteger messageIds = new AtomicInteger(100);
    private BoardingHouseTelegramBot bot;

    @BeforeEach
    void setup() throws Exception {
        TelegramConfig config = new TelegramConfig();
        config.setAdminId("123");
        StartHandler start = new StartHandler(stays, new BoardingHouseContactConfig("", "", ""));
        MenuHandler menu = new MenuHandler(users, mock(AuditLogService.class), mock(StatisticsService.class),
                states, start, new AuthorizationService(config));
        bot = new BoardingHouseTelegramBot(config, users, states, stays, List.of(start, menu));
        ReflectionTestUtils.setField(bot, "telegramClient", client);
        when(client.execute(any(SendMessage.class))).thenAnswer(call -> {
            Message sent = new Message();
            sent.setMessageId(messageIds.incrementAndGet());
            return sent;
        });
        when(stays.allowsMemberAction(any(), anyString(), anyString(), any())).thenReturn(true);
        when(stays.getCurrentResidence(123L)).thenReturn(new RoomStayService.CurrentResidence(new Room(), false));
    }

    static Stream<Arguments> commandsByRole() {
        return Arrays.stream(Role.values()).flatMap(role -> Stream.of("/start", "/menu", "/start@boarding_bot",
                "/menu@boarding_bot", "/start referral", "  /menu@boarding_bot  ")
                .map(command -> Arguments.of(role, command)));
    }

    @ParameterizedTest
    @MethodSource("commandsByRole")
    void commandsAndButtonsAlwaysSendNewMenusIncludingOldCallbacks(Role role, String command) throws Exception {
        user(role);
        bot.consume(text("/start"));
        states.setState(123L, new UserConversationState(role == Role.ADMIN
                ? UserConversationState.Step.ADMIN_SEARCH_USERS : role == Role.LANDLORD
                ? UserConversationState.Step.CREATE_ROOM_NUMBER : UserConversationState.Step.CREATE_MAINTENANCE_TITLE));
        bot.consume(text(command));

        assertEquals(UserConversationState.Step.NONE, states.getState(123L).getStep());
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(2)).execute(sent.capture());
        assertEquals(callbacks(TelegramKeyboardUtil.getRoleMenu(role)),
                callbacks((InlineKeyboardMarkup) sent.getValue().getReplyMarkup()));
        verify(client, never()).execute(any(EditMessageText.class));

        bot.consume(callback("ACCOUNT_INFO", 102));
        bot.consume(callback("MAIN_MENU", 103));
        verify(client, times(4)).execute(any(SendMessage.class));

        bot.consume(callback("ACCOUNT_INFO", 101));
        verify(client, times(5)).execute(any(SendMessage.class));
        ArgumentCaptor<AnswerCallbackQuery> answers = ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(client, times(3)).execute(answers.capture());
        assertNull(answers.getValue().getText());
        assertEquals(UserConversationState.Step.NONE, states.getState(123L).getStep());
    }

    @Test
    void unassignedMemberGetsNewMenuWithOnlyPermittedFunctions() throws Exception {
        user(Role.MEMBER);
        when(stays.getCurrentResidence(123L)).thenReturn(new RoomStayService.CurrentResidence(null, false));
        bot.consume(text("/start"));
        bot.consume(text("/menu"));
        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(2)).execute(sent.capture());
        for (SendMessage message : sent.getAllValues()) {
            assertEquals(callbacks(TelegramKeyboardUtil.getUnassignedMemberMenu()),
                    callbacks((InlineKeyboardMarkup) message.getReplyMarkup()));
        }
        verify(client, never()).execute(any(EditMessageText.class));
    }

    @Test
    void oldAdminMenuStillChecksCurrentRoleAndSendsPermissionError() throws Exception {
        user(Role.ADMIN);
        bot.consume(text("/start"));
        bot.consume(callback("ADMIN_USERS", 101));
        user(Role.MEMBER);
        bot.consume(callback("ADMIN_LIST_USERS", 102));
        ArgumentCaptor<SendMessage> messages = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(3)).execute(messages.capture());
        assertTrue(messages.getValue().getText().contains("không có quyền"));
        verify(users, never()).getAllUsers();
    }

    @ParameterizedTest
    @CsvSource({
            "ADMIN_PROMOTE_INPUT, /promote",
            "ADMIN_REVOKE_INPUT, /revoke"
    })
    void adminRoleChangePromptOffersBothCancelMethodsAndReturnsToUserManagement(
            String action, String expectedCommand) throws Exception {
        user(Role.ADMIN);
        bot.consume(text("/start"));
        bot.consume(callback("ADMIN_USERS", 101));
        bot.consume(callback(action, 102));

        ArgumentCaptor<SendMessage> messages = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(3)).execute(messages.capture());
        SendMessage prompt = messages.getAllValues().get(2);
        assertTrue(prompt.getText().contains("Nhập Telegram ID"));
        assertFalse(prompt.getText().contains(expectedCommand));
        assertFalse(prompt.getText().contains("/cancel"));
        // Active wizards expose Cancel; navigation resumes after cancellation.
        assertEquals(Set.of("/cancel"),
                callbacks((InlineKeyboardMarkup) prompt.getReplyMarkup()));
        assertEquals(UserConversationState.Step.ADMIN_ROLE_CHANGE_INPUT, states.getState(123L).getStep());

        clearInvocations(client);
        bot.consume(text("/cancel"));

        ArgumentCaptor<SendMessage> returned = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(returned.capture());
        SendMessage returnedMenu = returned.getValue();
        assertTrue(returnedMenu.getText().contains("QUẢN LÝ NGƯỜI DÙNG"));
        assertEquals(callbacks(TelegramKeyboardUtil.getAdminUserManagementMenu()),
                callbacks((InlineKeyboardMarkup) returnedMenu.getReplyMarkup()));
        assertEquals(UserConversationState.Step.NONE, states.getState(123L).getStep());

        clearInvocations(client);
        bot.consume(callback(action, 104));
        bot.consume(callback("/cancel", 105));

        ArgumentCaptor<SendMessage> buttonFlow = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(2)).execute(buttonFlow.capture());
        SendMessage buttonReturnedMenu = buttonFlow.getAllValues().get(1);
        assertTrue(buttonReturnedMenu.getText().contains("QUẢN LÝ NGƯỜI DÙNG"));
        assertEquals(callbacks(TelegramKeyboardUtil.getAdminUserManagementMenu()),
                callbacks((InlineKeyboardMarkup) buttonReturnedMenu.getReplyMarkup()));
        assertEquals(UserConversationState.Step.NONE, states.getState(123L).getStep());
    }

    @AfterEach
    void noMessageOrKeyboardIsEditedOrDeleted() throws Exception {
        verify(client, never()).execute(any(EditMessageText.class));
        verify(client, never()).execute(any(EditMessageReplyMarkup.class));
        verify(client, never()).execute(any(DeleteMessage.class));
    }

    private void user(Role role) {
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(123L, "user", "User", role));
    }

    private Set<String> callbacks(InlineKeyboardMarkup keyboard) {
        return keyboard.getKeyboard().stream().flatMap(List::stream)
                .map(button -> button.getCallbackData()).collect(Collectors.toSet());
    }

    private Update text(String text) {
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        message.setFrom(new org.telegram.telegrambots.meta.api.objects.User(123L, "User", false));
        message.setText(text);
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private Update callback(String action, int messageId) {
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        message.setMessageId(messageId);
        CallbackQuery callback = new CallbackQuery();
        callback.setId("callback_" + messageId + "_" + action);
        callback.setMessage(message);
        callback.setFrom(new org.telegram.telegrambots.meta.api.objects.User(123L, "User", false));
        callback.setData(action);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }
}
