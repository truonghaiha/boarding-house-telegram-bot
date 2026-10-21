package com.boardinghousebot.bot.handler;
import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.state.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AvailabilityRoomUiTest {
    RoomService rooms = mock(RoomService.class);
    ConversationStateService states = new ConversationStateService();
    BotMessageSender sender = mock(BotMessageSender.class);
    RoomCommandHandler handler = new RoomCommandHandler(rooms, states);
    User member = new User(71L, "new", "New Member", Role.MEMBER);
    User owner = new User(72L, "owner", "Owner", Role.LANDLORD);
    Room room() {
        Room r = new Room("101", new BigDecimal("3000000"), 3, "Private description");
        r.setId(1L); r.setArea(new BigDecimal("25.5")); r.setRequiredDeposit(new BigDecimal("1500000"));
        return r;
    }
    @Test void publicCardIncludesAllFieldsWithoutStatusOrTenantData() {
        Room r = room(); when(rooms.getAvailableRooms()).thenReturn(List.of(r));
        when(rooms.getCurrentOccupantCount(1L)).thenReturn(2);
        when(rooms.utilityPriceDisplay(any())).thenReturn("15000 VNĐ/m³");
        handler.handle(update("MEMBER_AVAILABLE_ROOMS", true), member, sender);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessageWithKeyboard(eq(71L), text.capture(), any());
        String content = text.getValue();
        assertTrue(content.contains("101")); assertTrue(content.contains("25.5 m²"));
        assertTrue(content.contains("3,000,000 VNĐ")); assertTrue(content.contains("1,500,000 VNĐ"));
        assertTrue(content.contains("2/3")); assertTrue(content.contains("Giá điện")); assertTrue(content.contains("Giá nước"));
        assertFalse(content.contains("Trạng thái")); assertFalse(content.contains("Private description"));
        verify(rooms, never()).getRoomTenants(anyLong()); verify(rooms, never()).getMemberRoom(anyLong());
    }
    @Test void emptyListAndOldPaginationAreHandled() {
        when(rooms.getAvailableRooms()).thenReturn(List.of());
        handler.handle(update("MEMBER_AVAILABLE_ROOMS_999", true), member, sender);
        verify(sender).sendMessageWithKeyboard(eq(71L), contains("Hiện chưa có phòng còn chỗ"), any());
    }
    @Test void editDepositRequiresConfirmationAndAllowsZero() {
        when(rooms.getRoomById(1L)).thenReturn(room()); when(rooms.getRoomTenants(1L)).thenReturn(List.of());
        handler.handle(update("ROOM_EDIT_DEPOSIT_1", true), owner, sender);
        handler.handle(update("-1", false), owner, sender);
        assertEquals(UserConversationState.Step.EDIT_ROOM_DEPOSIT, states.getState(72L).getStep());
        handler.handle(update("0", false), owner, sender);
        verify(rooms, never()).updateRoomDetails(anyLong(), anyLong(), any(), any());
        handler.handle(update("ROOM_EDIT_CONFIRM_1", true), owner, sender);
        verify(rooms).updateRoomDetails(72L, 1L, null, BigDecimal.ZERO);
        assertEquals(UserConversationState.Step.NONE, states.getState(72L).getStep());
    }
    @Test void memberCannotInvokeDepositEdit() {
        handler.handle(update("ROOM_EDIT_DEPOSIT_1", true), member, sender);
        verifyNoInteractions(rooms);
        verify(sender).sendNotice(eq(71L), contains("không có quyền"));
    }
    Update update(String action, boolean callback) {
        Message m = new Message(); m.setChat(new Chat(71L, "private"));
        Update u = new Update();
        if (callback) { CallbackQuery q = new CallbackQuery(); q.setData(action); q.setMessage(m); u.setCallbackQuery(q); }
        else { m.setText(action); u.setMessage(m); }
        return u;
    }
}
