package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.config.BoardingHouseContactConfig;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomStayService;
import com.boardinghousebot.service.RoomStayService.CurrentResidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemberStartFlowTest {
    @Mock RoomStayService stays;
    @Mock BotMessageSender sender;
    StartHandler handler;
    User member;

    @BeforeEach
    void setup() {
        handler = new StartHandler(stays, new BoardingHouseContactConfig("Nhà trọ An Bình", "0901234567", "chutro_ab"));
        member = new User(123L, null, "Nguyễn An", Role.MEMBER);
        member.setCreatedAt(LocalDateTime.of(2026, 10, 12, 9, 30));
    }

    @Test
    void newMemberStartShowsOnlyAccountAndStatusButtons() {
        when(stays.getCurrentResidence(123L)).thenReturn(new CurrentResidence(null, false));
        handler.handle(messageUpdate("/start"), member, sender);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendOrEditMessageWithKeyboard(eq(123L), isNull(), text.capture(), keyboard.capture());
        assertTrue(text.getValue().contains("chưa được ghi nhận là người thuê phòng"));
        assertTrue(text.getValue().contains("Vui lòng liên hệ trực tiếp với chủ trọ để được hỗ trợ."));
        assertFalse(text.getValue().contains("Nhà trọ An Bình"));
        assertFalse(text.getValue().contains("Nếu có nhu cầu thuê phòng hoặc đã đăng ký với chủ trọ"));
        assertEquals(List.of("MEMBER_AVAILABLE_ROOMS", "ACCOUNT_INFO", "MEMBER_CHECK_STATUS", "MEMBER_CONTACT_INFO"), callbacks(keyboard.getValue()));
    }

    @Test
    void startWithoutConfiguredContactShowsOnlyTheGenericContactLine() {
        StartHandler noContactHandler = new StartHandler(stays, new BoardingHouseContactConfig("", "", ""));
        when(stays.getCurrentResidence(123L)).thenReturn(new CurrentResidence(null, false));
        noContactHandler.handle(messageUpdate("/start"), member, sender);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).sendOrEditMessageWithKeyboard(eq(123L), isNull(), text.capture(), any());
        assertTrue(text.getValue().contains("Vui lòng liên hệ trực tiếp với chủ trọ để được hỗ trợ."));
        assertFalse(text.getValue().contains("Nếu có nhu cầu thuê phòng hoặc đã đăng ký với chủ trọ"));
        assertFalse(text.getValue().contains("THÔNG TIN LIÊN HỆ CHỦ TRỌ"));
    }

    @Test
    void contactButtonShowsConfiguredLandlordContactAndKeepsUnassignedMenu() {
        when(stays.getCurrentResidence(123L)).thenReturn(new CurrentResidence(null, false));
        handler.showMemberContact(callbackUpdate("MEMBER_CONTACT_INFO"), member, sender);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendOrEditMessageWithKeyboard(eq(123L), eq(10), text.capture(), keyboard.capture());
        assertTrue(text.getValue().contains("THÔNG TIN LIÊN HỆ CHỦ TRỌ"));
        assertTrue(text.getValue().contains("Nhà trọ An Bình"));
        assertTrue(text.getValue().contains("0901234567"));
        assertTrue(text.getValue().contains("@chutro_ab"));
        assertTrue(callbacks(keyboard.getValue()).contains("MEMBER_CONTACT_INFO"));
    }

    @Test
    void accountForUnassignedMemberOmitsRoleAndHandlesMissingUsername() {
        when(stays.getCurrentResidence(123L)).thenReturn(new CurrentResidence(null, false));
        handler.showMemberAccount(callbackUpdate("ACCOUNT_INFO"), member, sender);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).sendOrEditMessageWithKeyboard(eq(123L), eq(10), text.capture(), any());
        assertTrue(text.getValue().contains("Username: Chưa đặt"));
        assertTrue(text.getValue().contains("Chưa thuê phòng"));
        assertFalse(text.getValue().contains("Vai trò: MEMBER"));
    }

    @Test
    void checkStatusSwitchesTheSameMessageToFullMemberMenu() {
        Room room = new Room("P101", new BigDecimal("3000000"), 2, "");
        room.setId(1L);
        when(stays.getCurrentResidence(123L)).thenReturn(new CurrentResidence(room, false));
        handler.checkMemberStatus(callbackUpdate("MEMBER_CHECK_STATUS"), member, sender);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendOrEditMessageWithKeyboard(eq(123L), eq(10), text.capture(), keyboard.capture());
        assertTrue(text.getValue().contains("phòng P101"));
        assertTrue(callbacks(keyboard.getValue()).contains("MEMBER_ROOM"));
        assertFalse(callbacks(keyboard.getValue()).contains("MEMBER_CHECK_STATUS"));
    }

    @Test
    void rentingMemberCanOpenContactAndKeepFullMenu() {
        Room room = new Room("P101", new BigDecimal("3000000"), 2, "");
        room.setId(1L);
        when(stays.getCurrentResidence(123L)).thenReturn(new CurrentResidence(room, false));
        handler.showMemberContact(callbackUpdate("MEMBER_CONTACT_INFO"), member, sender);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendOrEditMessageWithKeyboard(eq(123L), eq(10), text.capture(), keyboard.capture());
        assertTrue(text.getValue().contains("0901234567"));
        assertTrue(text.getValue().contains("@chutro_ab"));
        assertTrue(callbacks(keyboard.getValue()).contains("MEMBER_CONTACT_INFO"));
        assertTrue(callbacks(keyboard.getValue()).contains("MEMBER_ROOM"));
    }

    @Test
    void databaseFailureDoesNotClaimMemberIsUnassigned() {
        when(stays.getCurrentResidence(123L)).thenThrow(new DataAccessResourceFailureException("offline"));
        handler.checkMemberStatus(callbackUpdate("MEMBER_CHECK_STATUS"), member, sender);

        verify(sender).sendMessage(123L, StartHandler.STATUS_ERROR);
        verify(sender, never()).sendOrEditMessageWithKeyboard(anyLong(), any(), contains("chưa được xếp"), any());
    }

    @Test
    void adminStartKeepsExistingMenuFlow() {
        User admin = new User(999L, "admin", "Quản trị", Role.ADMIN);
        handler.handle(messageUpdateFor("/start", 999L), admin, sender);
        verify(sender).sendMessageWithKeyboard(eq(999L), contains("ADMIN PANEL"), any());
        verifyNoInteractions(stays);
    }

    private List<String> callbacks(InlineKeyboardMarkup markup) {
        return markup.getKeyboard().stream().flatMap(List::stream).map(button -> button.getCallbackData()).toList();
    }

    private Update messageUpdate(String text) { return messageUpdateFor(text, 123L); }

    private Update messageUpdateFor(String text, Long id) {
        Message message = new Message();
        message.setChat(new Chat(id, "private"));
        message.setText(text);
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private Update callbackUpdate(String data) {
        Message message = new Message();
        message.setMessageId(10);
        message.setChat(new Chat(123L, "private"));
        CallbackQuery callback = new CallbackQuery();
        callback.setData(data);
        callback.setMessage(message);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }
}
