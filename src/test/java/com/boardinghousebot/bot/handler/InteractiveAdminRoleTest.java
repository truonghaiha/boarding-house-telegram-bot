package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.*;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InteractiveAdminRoleTest {
    final UserService users = mock(UserService.class);
    final BotMessageSender sender = mock(BotMessageSender.class);
    final ConversationStateService states = new ConversationStateService();
    final TelegramConfig config = config();
    final MenuHandler handler = new MenuHandler(users, mock(AuditLogService.class), mock(StatisticsService.class), states,
            mock(StartHandler.class), new AuthorizationService(config));
    final User admin = new User(99L, "admin", "Admin", Role.ADMIN);
    final User target = new User(11L, "target", "An", Role.MEMBER);
    static TelegramConfig config() { var config = new TelegramConfig(); config.setAdminId("99"); return config; }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void roleChangeUsesPlainIdAndOnlyExecutesOnConfirmation(boolean promote) {
        when(users.findByTelegramIdWithRoom(11L)).thenReturn(Optional.of(target));
        when(users.promoteToLandlord(99L, 11L)).thenReturn(target);
        when(users.revokeLandlord(99L, 11L)).thenReturn(target);
        click(promote ? "ADMIN_PROMOTE_INPUT" : "ADMIN_REVOKE_INPUT");
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(s -> s.contains("Nhập Telegram ID") && !s.contains("/promote") && !s.contains("/revoke")), any());
        text("bad"); text("404"); assertEquals(Step.ADMIN_ROLE_CHANGE_INPUT, states.getState(99L).getStep());
        text("11"); assertEquals(Step.ADMIN_ROLE_CHANGE_CONFIRM, states.getState(99L).getStep());
        verify(users, never()).promoteToLandlord(anyLong(), anyLong()); verify(users, never()).revokeLandlord(anyLong(), anyLong());
        click("ADMIN_ROLE_CONFIRM_12"); verify(users, never()).promoteToLandlord(anyLong(), anyLong());
        click("ADMIN_ROLE_CONFIRM_11");
        if (promote) verify(users).promoteToLandlord(99L, 11L); else verify(users).revokeLandlord(99L, 11L);
        assertEquals(Step.NONE, states.getState(99L).getStep());
        click("ADMIN_ROLE_CONFIRM_11");
        if (promote) verify(users, times(1)).promoteToLandlord(anyLong(), anyLong()); else verify(users, times(1)).revokeLandlord(anyLong(), anyLong());
    }
    @Test void cancelAndLostAdminPermissionDoNotChangeRoles() {
        when(users.findByTelegramIdWithRoom(11L)).thenReturn(Optional.of(target));
        click("ADMIN_PROMOTE_INPUT"); text("11"); text("/cancel"); click("ADMIN_ROLE_CONFIRM_11");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        click("ADMIN_PROMOTE_INPUT"); text("11"); config.setAdminId("1000"); click("ADMIN_ROLE_CONFIRM_11");
        verify(users, never()).promoteToLandlord(anyLong(), anyLong());
    }
    void click(String action) { handler.handle(update(action, true), admin, sender); }
    void text(String value) { handler.handle(update(value, false), admin, sender); }
    Update update(String value, boolean callback) {
        Message message = new Message(); message.setChat(new Chat(99L, "private")); Update update = new Update();
        if (callback) { CallbackQuery query = new CallbackQuery(); query.setMessage(message); query.setData(value); update.setCallbackQuery(query); }
        else { message.setText(value); update.setMessage(message); } return update;
    }
}
