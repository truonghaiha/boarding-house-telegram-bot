package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.state.ConversationStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomDetailDisplayTest {
    private final RoomService service = mock(RoomService.class);
    private final BotMessageSender sender = mock(BotMessageSender.class);
    private final RoomCommandHandler handler = new RoomCommandHandler(service, new ConversationStateService());
    private final User landlord = new User();
    private final Room room = new Room("102", new BigDecimal("5000000"), 5, "phòng đẹp giá sinh viên rmit");

    @BeforeEach
    void setUp() {
        landlord.setTelegramId(123L);
        landlord.setRole(Role.LANDLORD);
        room.setId(2L);
        when(service.getRoomById(2L)).thenReturn(room);
        when(service.getRoomTenants(2L)).thenReturn(List.of());
        when(service.getAllRooms()).thenReturn(List.of(room));
        when(service.getAvailableRooms()).thenReturn(List.of(room));
        when(service.getOccupiedRooms()).thenReturn(List.of(room));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/room_detail_2", "/room_detail_2@boarding_bot", "ROOM_DETAIL_2"})
    void detailDisplaysSavedDescription(String action) {
        handler.handle(update(action), landlord, sender);
        if (action.startsWith("/")) {
            verify(sender).sendMessageWithKeyboard(eq(123L),
                    argThat(text -> text.contains("🏠 CHI TIẾT PHÒNG 102") && text.contains(room.getDescription())), any());
        } else {
            verify(sender).sendOrEditMessageWithKeyboard(eq(123L), eq(77),
                    argThat(text -> text.contains("🏠 CHI TIẾT PHÒNG 102") && text.contains(room.getDescription())), any());
        }
        verify(service).getRoomById(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_LIST_AVAILABLE", "ROOM_LIST_OCCUPIED"})
    void listDisplaysDescriptionAndWorkingDetailButton(String action) {
        handler.handle(update(action), landlord, sender);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendMessageWithKeyboard(eq(123L), contains(room.getDescription()), keyboard.capture());
        assertTrue(keyboard.getValue().getKeyboard().stream().flatMap(List::stream)
                .anyMatch(button -> "ROOM_DETAIL_2".equals(button.getCallbackData())));
    }

    @Test
    void blankDescriptionHasFallback() {
        room.setDescription("  ");
        handler.handle(update("/room_detail_2"), landlord, sender);
        verify(sender).sendMessageWithKeyboard(eq(123L),
                contains("- Chưa có người ở\n\n📝 Mô tả: Chưa có mô tả"), any());
    }

    @Test
    void malformedDetailCommandShowsUsefulError() {
        handler.handle(update("/room_detail_abc"), landlord, sender);
        verify(sender).sendNotice(123L, "❌ Mã phòng không hợp lệ.");
        verify(service, never()).getRoomById(anyLong());
    }

    private Update update(String action) {
        Message message = new Message();
        message.setMessageId(77);
        message.setChat(new Chat(123L, "private"));
        Update update = new Update();
        if (action.startsWith("/")) {
            message.setText(action);
            update.setMessage(message);
        } else {
            CallbackQuery callback = new CallbackQuery();
            callback.setMessage(message);
            callback.setData(action);
            update.setCallbackQuery(callback);
        }
        return update;
    }
}
