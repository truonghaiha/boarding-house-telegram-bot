package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState.Step;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomTenantRemovalTest {
    private final RoomService rooms = mock(RoomService.class);
    private final ConversationStateService states = new ConversationStateService();
    private final RoomCommandHandler handler = new RoomCommandHandler(rooms, states);
    private final BotMessageSender sender = mock(BotMessageSender.class);
    private final User landlord = new User(99L, "owner", "Chủ trọ", Role.LANDLORD);
    private final User tenant = new User(11L, "tenant", "Hoàng", Role.MEMBER);
    private final Room room = new Room("101", new BigDecimal("4000000"), 2, "");

    @BeforeEach
    void setup() {
        room.setId(1L);
        room.setStatus(RoomStatus.OCCUPIED);
        tenant.setRoom(room);
        when(rooms.getRoomById(1L)).thenReturn(room);
        when(rooms.getRoomTenants(1L)).thenReturn(List.of(tenant));
        when(rooms.getMemberActiveContractId(11L)).thenReturn(Optional.empty());
    }

    @Test
    void selectionRequiresConfirmationEvenForOnlyResidentThenRefreshesRoom() {
        click("ROOM_REMOVE_TENANTS_1");
        assertEquals(Step.REMOVE_ROOM_TENANT_SELECT, states.getState(99L).getStep());
        assertEquals(List.of("ROOM_REMOVE_SELECT_1_11", "CANCEL_ROOM_REMOVE_TENANT"), buttons());
        clearInvocations(sender);
        click("ROOM_REMOVE_SELECT_1_11");
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
        assertEquals(Step.REMOVE_ROOM_TENANT_CONFIRM, states.getState(99L).getStep());
        assertEquals(List.of("ROOM_REMOVE_CONFIRM_1_11", "CANCEL_ROOM_REMOVE_TENANT"), buttons());
        when(rooms.removeMemberFromRoom(99L, 11L, 1L)).thenAnswer(call -> {
            when(rooms.getRoomTenants(1L)).thenReturn(List.of());
            room.setStatus(RoomStatus.AVAILABLE);
            return tenant;
        });
        click("ROOM_REMOVE_CONFIRM_1_11");
        verify(rooms).removeMemberFromRoom(99L, 11L, 1L);
        assertEquals(Step.NONE, states.getState(99L).getStep());
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(text -> text.contains("0/2") && text.contains("Đang trống")), any());
        click("ROOM_REMOVE_CONFIRM_1_11");
        verify(rooms, times(1)).removeMemberFromRoom(99L, 11L, 1L);
    }

    @Test
    void cancelButtonAndCancelCommandNeverRemoveTenant() {
        for (String cancel : List.of("CANCEL_ROOM_REMOVE_TENANT", "/cancel")) {
            click("ROOM_REMOVE_TENANTS_1");
            click("ROOM_REMOVE_SELECT_1_11");
            if (cancel.startsWith("/")) {
                Message message = new Message();
                message.setChat(new Chat(99L, "private"));
                message.setText(cancel);
                Update update = new Update();
                update.setMessage(message);
                handler.handle(update, landlord, sender);
            } else click(cancel);
            assertEquals(Step.NONE, states.getState(99L).getStep());
            click("ROOM_REMOVE_CONFIRM_1_11");
        }
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
    }

    @Test
    void contractRepresentativeIsBlockedAndGetsContractLink() {
        when(rooms.getMemberActiveContractId(11L)).thenReturn(Optional.of(7L));
        click("ROOM_REMOVE_TENANTS_1");
        clearInvocations(sender);
        click("ROOM_REMOVE_SELECT_1_11");
        assertEquals(List.of("CONTRACT_DETAIL_7", "CANCEL_ROOM_REMOVE_TENANT"), buttons());
        assertEquals(Step.REMOVE_ROOM_TENANT_SELECT, states.getState(99L).getStep());
        click("ROOM_REMOVE_CONFIRM_1_11");
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
    }

    @Test
    void contractCreatedAfterConfirmationPromptIsAlsoBlocked() {
        click("ROOM_REMOVE_TENANTS_1");
        click("ROOM_REMOVE_SELECT_1_11");
        when(rooms.getMemberActiveContractId(11L)).thenReturn(Optional.of(7L));
        click("ROOM_REMOVE_CONFIRM_1_11");
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
    }

    @Test
    void wrongTenantAndTenantWhoLeftCannotBeRemoved() {
        click("ROOM_REMOVE_TENANTS_1");
        click("ROOM_REMOVE_SELECT_1_11");
        click("ROOM_REMOVE_CONFIRM_1_12");
        when(rooms.getRoomTenants(1L)).thenReturn(List.of());
        click("ROOM_REMOVE_CONFIRM_1_11");
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
        assertEquals(Step.NONE, states.getState(99L).getStep());
    }

    @Test
    void memberCannotUseRemovalAndEmptyRoomHasNoWizard() {
        Update update = callback("ROOM_REMOVE_TENANTS_1");
        handler.handle(update, tenant, sender);
        verifyNoInteractions(rooms);
        when(rooms.getRoomTenants(1L)).thenReturn(List.of());
        click("ROOM_REMOVE_TENANTS_1");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
    }

    @Test
    void deleteRoomIsLastManagementActionOnItsOwnRow() {
        var rows = TelegramKeyboardUtil.getRoomDetailKeyboard(1L).getKeyboard();
        assertEquals("ROOM_REMOVE_TENANTS_1", rows.get(1).get(1).getCallbackData());
        assertEquals(1, rows.get(4).size());
        assertEquals("ROOM_DELETE_1", rows.get(4).get(0).getCallbackData());
    }

    private List<String> buttons() {
        var captor = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendMessageWithKeyboard(eq(99L), anyString(), captor.capture());
        return captor.getValue().getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList();
    }

    private void click(String action) { handler.handle(callback(action), landlord, sender); }

    private Update callback(String action) {
        Message message = new Message();
        message.setChat(new Chat(99L, "private"));
        message.setMessageId(1);
        CallbackQuery query = new CallbackQuery();
        query.setData(action);
        query.setMessage(message);
        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }
}
