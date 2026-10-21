package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AdminMenuAuthorizationTest {
    @ParameterizedTest
    @ValueSource(strings = {"ADMIN_LIST_USERS", "ADMIN_AUDIT", "ADMIN_STATS", "ADMIN_PROMOTE_INPUT", "ADMIN_REVOKE_INPUT",
            "/promote 2", "/revoke 2", "/search_users name", "unfinished search"})
    void staleAdminCannotUseOldButtonsCommandsOrPendingSearch(String action) {
        TelegramConfig config = new TelegramConfig();
        config.setAdminId("2");
        User stale = new User(1L, "old", "Old", Role.ADMIN);
        ConversationStateService states = new ConversationStateService();
        if (action.equals("unfinished search")) {
            states.setState(1L, new UserConversationState(UserConversationState.Step.ADMIN_SEARCH_USERS));
        }
        UserService users = mock(UserService.class);
        AuditLogService audit = mock(AuditLogService.class);
        StatisticsService stats = mock(StatisticsService.class);
        StartHandler start = mock(StartHandler.class);
        BotMessageSender sender = mock(BotMessageSender.class);
        MenuHandler handler = new MenuHandler(users, audit, stats, states, start, new AuthorizationService(config));
        Message message = new Message();
        message.setChat(new Chat(1L, "private"));
        Update update = new Update();
        if (action.startsWith("ADMIN_")) {
            CallbackQuery callback = new CallbackQuery();
            callback.setMessage(message);
            callback.setData(action);
            update.setCallbackQuery(callback);
        } else {
            message.setText(action);
            update.setMessage(message);
        }
        handler.handle(update, stale, sender);
        verify(sender).sendNotice(eq(1L), contains("không có quyền"));
        verifyNoInteractions(users, audit, stats, start);
        assertEquals(UserConversationState.Step.NONE, states.getState(1L).getStep());
    }
}
