package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantContactFlowTest {
    final RoomService rooms = mock(RoomService.class);
    final UserService users = mock(UserService.class);
    final ConversationStateService states = new ConversationStateService();
    final BotMessageSender sender = mock(BotMessageSender.class);
    final TenantCommandHandler handler = new TenantCommandHandler(rooms, users, states, mock(ContractRepository.class));
    final User landlord = new User(99L, "owner", "Chủ trọ", Role.LANDLORD);
    final User tenant = new User(11L, "tenant", "Hoàng", Role.MEMBER);
    final Room room = new Room("101", new BigDecimal("4000000"), 2, "");

    @BeforeEach void setup() {
        room.setId(1L);
        when(rooms.getRoomById(1L)).thenReturn(room);
        when(rooms.getRoomByRoomNumber("101")).thenReturn(Optional.of(room));
        when(users.findByTelegramIdWithRoom(11L)).thenReturn(Optional.of(tenant));
    }

    @Test void bothAssignmentEntriesRequireValidPhoneBeforeSaving() {
        for (boolean command : List.of(true, false)) {
            states.clearState(99L);
            if (command) text("/assign 11 101");
            else { click("ROOM_ASSIGN_1"); text("11"); }
            assertEquals(Step.ASSIGN_TENANT_PHONE, states.getState(99L).getStep());
            verify(rooms, never()).assignMemberToRoom(anyLong(), anyLong(), anyLong());
            for (String invalid : List.of("", "abc", "/skip", "123")) {
                text(invalid);
                assertEquals(Step.ASSIGN_TENANT_PHONE, states.getState(99L).getStep());
            }
            when(rooms.assignMemberToRoom(99L, 11L, 1L, "0912345678")).thenAnswer(call -> {
                tenant.setPhoneNumber("0912345678"); return tenant;
            });
            text("0912 345 678");
            assertEquals(Step.NONE, states.getState(99L).getStep());
        }
        verify(rooms, times(2)).assignMemberToRoom(99L, 11L, 1L, "0912345678");
    }

    @Test void cancelAssignmentDoesNotSavePhoneOrRoom() {
        text("/assign 11 101"); text("/cancel");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        assertNull(tenant.getPhoneNumber());
        verify(rooms, never()).assignMemberToRoom(anyLong(), anyLong(), anyLong(), any());
    }

    @Test void editRequiresValidIdAndConfirmationAndListsPhoneWithoutDeleteCommand() {
        tenant.setRoom(room); tenant.setPhoneNumber("0912345678");
        when(users.getUsersByRoleWithRoom(Role.MEMBER)).thenReturn(List.of(tenant));
        click("TENANT_EDIT_INPUT"); text("bad"); text("404");
        assertEquals(Step.EDIT_TENANT_TELEGRAM_ID, states.getState(99L).getStep());
        text("11"); text("abc");
        assertEquals(Step.EDIT_TENANT_PHONE, states.getState(99L).getStep());
        text("+84 912 345 679");
        assertEquals(Step.EDIT_TENANT_CONFIRM, states.getState(99L).getStep());
        verify(users, never()).updateTenantPhoneNumber(anyLong(), anyLong(), anyString());
        when(users.updateTenantPhoneNumber(99L, 11L, "+84912345679")).thenAnswer(call -> {
            tenant.setPhoneNumber("+84912345679"); return tenant;
        });
        click("TENANT_EDIT_CONFIRM");
        verify(users).updateTenantPhoneNumber(99L, 11L, "+84912345679");
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(s -> s.contains("DANH SÁCH NGƯỜI THUÊ")
                && s.contains("+84912345679") && !s.contains("Lệnh xóa") && !s.contains("/remove_tenant")), any());
        click("TENANT_EDIT_CONFIRM");
        verify(users, times(1)).updateTenantPhoneNumber(anyLong(), anyLong(), anyString());
    }

    @Test void cancelEveryEditStepLeavesExistingPhoneUntouched() {
        tenant.setRoom(room); tenant.setPhoneNumber("0912345678");
        for (int step = 0; step < 3; step++) {
            click("TENANT_EDIT_INPUT");
            if (step > 0) text("11");
            if (step > 1) text("0987654321");
            text("/cancel");
            assertEquals(Step.NONE, states.getState(99L).getStep());
            assertEquals("0912345678", tenant.getPhoneNumber());
        }
        verify(users, never()).updateTenantPhoneNumber(anyLong(), anyLong(), anyString());
    }

    @Test void memberCannotEditAndUnassignedAccountCannotBeEdited() {
        handler.handle(update("TENANT_EDIT_INPUT", false), tenant, sender);
        verifyNoInteractions(users, rooms);
        click("TENANT_EDIT_INPUT"); text("11");
        assertEquals(Step.EDIT_TENANT_TELEGRAM_ID, states.getState(99L).getStep());
    }

    @Test void roomTenantListShowsPhoneAndLegacyPlaceholder() {
        when(rooms.getRoomTenants(1L)).thenReturn(List.of(tenant));
        click("ROOM_TENANTS_1");
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("Số điện thoại: Chưa cập nhật"), any());
        tenant.setPhoneNumber("0912345678"); click("ROOM_TENANTS_1");
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("Số điện thoại: 0912345678"), any());
    }

    void click(String action) { handler.handle(update(action, false), landlord, sender); }
    void text(String text) { handler.handle(update(text, true), landlord, sender); }
    Update update(String value, boolean text) {
        Message message = new Message(); message.setChat(new Chat(99L, "private"));
        Update update = new Update();
        if (text) { message.setText(value); update.setMessage(message); }
        else { CallbackQuery query = new CallbackQuery(); query.setData(value); query.setMessage(message); update.setCallbackQuery(query); }
        return update;
    }
}
