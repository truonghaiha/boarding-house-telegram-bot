package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState.Step;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomEditFlowTest {
    final RoomService rooms = mock(RoomService.class);
    final ConversationStateService states = new ConversationStateService();
    final BotMessageSender sender = mock(BotMessageSender.class);
    final RoomCommandHandler handler = new RoomCommandHandler(rooms, states);
    final User owner = new User(99L, "owner", "Owner", Role.LANDLORD);
    final Room room = new Room("202", new BigDecimal("4000000"), 2, "Mô tả cũ");
    @BeforeEach void setup() {
        room.setId(1L); when(rooms.getRoomById(1L)).thenReturn(room); when(rooms.getRoomTenants(1L)).thenReturn(List.of());
    }
    @Test void rentMustBePositiveAndOnlySavesOnConfirmation() {
        click("ROOM_EDIT_RENT_1");
        for (String invalid : List.of("abc", "0", "-1")) {
            text(invalid); assertEquals(Step.EDIT_ROOM_RENT, states.getState(99L).getStep());
        }
        text("5000000"); assertEquals(Step.EDIT_ROOM_CONFIRM, states.getState(99L).getStep());
        verify(rooms, never()).updateRoomRent(anyLong(), anyLong(), any());
        when(rooms.updateRoomRent(99L, 1L, new BigDecimal("5000000"))).thenAnswer(call -> {
            room.setMonthlyRent(new BigDecimal("5000000")); return room;
        });
        click("ROOM_EDIT_CONFIRM_1");
        verify(rooms).updateRoomRent(99L, 1L, new BigDecimal("5000000"));
        assertEquals(Step.NONE, states.getState(99L).getStep());
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(s -> s.contains("CHI TIẾT PHÒNG 202") && s.contains("5,000,000 VNĐ")), any());
        click("ROOM_EDIT_CONFIRM_1"); verify(rooms, times(1)).updateRoomRent(anyLong(), anyLong(), any());
    }
    @Test void descriptionOnlySavesOnConfirmation() {
        click("ROOM_EDIT_DESC_1"); text(" "); assertEquals(Step.EDIT_ROOM_DESC, states.getState(99L).getStep());
        text("  Mô tả mới  ");
        verify(rooms, never()).updateRoomDescription(anyLong(), anyLong(), any());
        when(rooms.updateRoomDescription(99L, 1L, "Mô tả mới")).thenAnswer(call -> { room.setDescription("Mô tả mới"); return room; });
        click("ROOM_EDIT_CONFIRM_1"); verify(rooms).updateRoomDescription(99L, 1L, "Mô tả mới");
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("📝 Mô tả: Mô tả mới"), any());
    }
    @Test void cancelBeforeAndAfterPreviewNeverSaves() {
        for (String start : List.of("ROOM_EDIT_RENT_1", "ROOM_EDIT_DESC_1")) {
            click(start); text("/cancel"); assertEquals(Step.NONE, states.getState(99L).getStep());
            click(start); text(start.contains("RENT") ? "5000000" : "Mới"); text("/cancel");
            click("ROOM_EDIT_CONFIRM_1");
        }
        verify(rooms, never()).updateRoomRent(anyLong(), anyLong(), any());
        verify(rooms, never()).updateRoomDescription(anyLong(), anyLong(), any());
    }
    @Test void wrongRoomConfirmationCannotSaveAndOtherRolesCannotEdit() {
        click("ROOM_EDIT_RENT_1"); text("5000000"); click("ROOM_EDIT_CONFIRM_2");
        verify(rooms, never()).updateRoomRent(anyLong(), anyLong(), any());
        for (Role role : List.of(Role.MEMBER, Role.ADMIN)) {
            clearInvocations(rooms); handler.handle(update("ROOM_EDIT_DESC_1", true), new User(88L, "other", "Other", role), sender);
            verifyNoInteractions(rooms);
        }
    }
    @Test void detailRowsMatchRequestedOrder() {
        var rows = TelegramKeyboardUtil.getRoomDetailKeyboard(1L).getKeyboard();
        assertEquals(List.of("👥 Người thuê", "🚚 Chuyển phòng"), rows.get(0).stream().map(b -> b.getText()).toList());
        assertEquals(List.of("➕ Thêm người thuê", "➖ Xóa người thuê"), rows.get(1).stream().map(b -> b.getText()).toList());
        assertEquals(List.of("ROOM_EDIT_RENT_1", "ROOM_EDIT_DESC_1"), rows.get(2).stream().map(b -> b.getCallbackData()).toList());
        assertEquals(List.of("ROOM_EDIT_AREA_1", "ROOM_EDIT_DEPOSIT_1"), rows.get(3).stream().map(b -> b.getCallbackData()).toList());
        assertEquals("ROOM_DELETE_1", rows.get(4).get(0).getCallbackData());
    }
    void click(String action) { handler.handle(update(action, true), owner, sender); }
    void text(String value) { handler.handle(update(value, false), owner, sender); }
    Update update(String value, boolean callback) {
        Message message = new Message(); message.setChat(new Chat(99L, "private")); Update update = new Update();
        if (callback) { CallbackQuery query = new CallbackQuery(); query.setMessage(message); query.setData(value); update.setCallbackQuery(query); }
        else { message.setText(value); update.setMessage(message); }
        return update;
    }
}
