package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.contract.Contract;
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
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InteractiveTenantActionsTest {
    final RoomService rooms = mock(RoomService.class);
    final UserService users = mock(UserService.class);
    final ContractRepository contracts = mock(ContractRepository.class);
    final BotMessageSender sender = mock(BotMessageSender.class);
    final ConversationStateService states = new ConversationStateService();
    final TenantCommandHandler handler = new TenantCommandHandler(rooms, users, states, contracts);
    final User owner = new User(99L, "owner", "Owner", Role.LANDLORD);
    final User tenant = new User(11L, "tenant", "An", Role.MEMBER);
    final Room first = new Room("101", new BigDecimal("4000000"), 2, "");
    final Room second = new Room("102", new BigDecimal("4000000"), 2, "");
    @BeforeEach void setup() {
        first.setId(1L); second.setId(2L);
        when(users.findByTelegramIdWithRoom(11L)).thenReturn(Optional.of(tenant));
        when(rooms.getAvailableRooms()).thenReturn(List.of(first, second));
        when(rooms.getAllRooms()).thenReturn(List.of(first, second));
        when(rooms.getRoomById(1L)).thenReturn(first); when(rooms.getRoomById(2L)).thenReturn(second);
        when(contracts.findActiveContractByMemberTelegramId(11L)).thenReturn(Optional.empty());
    }
    @Test void addUsesIdThenRoomButtonThenMandatoryPhone() {
        click("TENANT_ADD_INPUT"); assertEquals(Step.ASSIGN_TENANT_TELEGRAM_ID, states.getState(99L).getStep());
        text("bad"); assertEquals(Step.ASSIGN_TENANT_TELEGRAM_ID, states.getState(99L).getStep());
        text("11"); assertEquals(Step.ASSIGN_TENANT_ROOM_SELECT, states.getState(99L).getStep());
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("Chọn phòng"), argThat(k -> k.getKeyboard().stream().flatMap(List::stream)
                .anyMatch(b -> "TENANT_ASSIGN_ROOM_1_11".equals(b.getCallbackData()))));
        click("TENANT_ASSIGN_ROOM_1_12"); assertEquals(Step.ASSIGN_TENANT_ROOM_SELECT, states.getState(99L).getStep());
        click("TENANT_ASSIGN_ROOM_1_11"); assertEquals(Step.ASSIGN_TENANT_PHONE, states.getState(99L).getStep());
        verify(rooms, never()).assignMemberToRoom(anyLong(), anyLong(), anyLong(), any());
        when(rooms.assignMemberToRoom(99L, 11L, 1L, "0912345678")).thenReturn(tenant);
        text("0912345678"); verify(rooms).assignMemberToRoom(99L, 11L, 1L, "0912345678");
    }
    @Test void addCanBeCancelledAtEachInputStep() {
        for (int step = 0; step < 3; step++) {
            click("TENANT_ADD_INPUT"); if (step > 0) text("11"); if (step > 1) click("TENANT_ASSIGN_ROOM_1_11");
            text("/cancel"); assertEquals(Step.NONE, states.getState(99L).getStep());
        }
        verify(rooms, never()).assignMemberToRoom(anyLong(), anyLong(), anyLong(), any());
    }
    @Test void removeUsesIdAndConfirmationAndRejectsStaleButton() {
        tenant.setRoom(first); click("TENANT_REMOVE_INPUT"); text("11");
        assertEquals(Step.REMOVE_TENANT_CONFIRM, states.getState(99L).getStep());
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
        click("TENANT_REMOVE_CONFIRM_2_11"); verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
        when(rooms.removeMemberFromRoom(99L, 11L, 1L)).thenReturn(tenant);
        click("TENANT_REMOVE_CONFIRM_1_11"); verify(rooms).removeMemberFromRoom(99L, 11L, 1L);
        click("TENANT_REMOVE_CONFIRM_1_11"); verify(rooms, times(1)).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
    }
    @Test void removeCancellationAndActiveRepresentativeNeverRemove() {
        tenant.setRoom(first); click("TENANT_REMOVE_INPUT"); text("11"); text("/cancel");
        click("TENANT_REMOVE_CONFIRM_1_11");
        when(contracts.findActiveContractByMemberTelegramId(11L)).thenReturn(Optional.of(mock(Contract.class)));
        click("TENANT_REMOVE_INPUT"); text("11"); assertEquals(Step.REMOVE_TENANT_INPUT, states.getState(99L).getStep());
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
    }
    @Test void moveFromRoomUsesIdRoomChoiceAndConfirmation() {
        tenant.setRoom(first); click("ROOM_MOVE_1"); text("11");
        assertEquals(Step.MOVE_TENANT_TARGET_ROOM_SELECT, states.getState(99L).getStep());
        click("SELECT_MOVE_TARGET_ROOM_2"); assertEquals(Step.MOVE_TENANT_CONFIRM, states.getState(99L).getStep());
        verify(rooms, never()).moveMemberToRoom(anyLong(), anyLong(), anyLong());
        User moved = new User(11L, "tenant", "An", Role.MEMBER); moved.setRoom(second);
        when(rooms.moveMemberToRoom(99L, 11L, 2L)).thenReturn(moved);
        click("CONFIRM_MOVE_TENANT"); verify(rooms).moveMemberToRoom(99L, 11L, 2L);
        click("CONFIRM_MOVE_TENANT"); verify(rooms, times(1)).moveMemberToRoom(anyLong(), anyLong(), anyLong());
    }
    @Test void moveFromWrongSourceRoomIsRejected() {
        tenant.setRoom(second); click("ROOM_MOVE_1"); text("11");
        assertEquals(Step.MOVE_TENANT_MEMBER_TELEGRAM_ID, states.getState(99L).getStep());
        verify(sender).sendNotice(eq(99L), contains("không thuộc phòng đã chọn"));
    }
    @Test void legacyRemoveCallbackAlsoRequiresConfirmation() {
        tenant.setRoom(first); click("REMOVE_TENANT_11");
        assertEquals(Step.REMOVE_TENANT_CONFIRM, states.getState(99L).getStep());
        verify(rooms, never()).removeMemberFromRoom(anyLong(), anyLong(), anyLong());
    }
    @Test void promptsDoNotRequireCommandsAndMembersCannotUseThem() {
        click("TENANT_ADD_INPUT"); verify(sender).sendPrompt(eq(99L), argThat(s -> s.contains("Nhập Telegram ID") && !s.contains("/assign")));
        states.clearState(99L); clearInvocations(sender);
        click("TENANT_REMOVE_INPUT"); verify(sender).sendPrompt(eq(99L), argThat(s -> s.contains("XÓA NGƯỜI THUÊ") && !s.contains("/remove_tenant")));
        handler.handle(update("TENANT_ADD_INPUT", true), tenant, sender);
        verify(sender).sendNotice(eq(99L), contains("không có quyền"));
    }
    void click(String action) { handler.handle(update(action, true), owner, sender); }
    void text(String value) { handler.handle(update(value, false), owner, sender); }
    Update update(String value, boolean callback) {
        Message message = new Message(); message.setChat(new Chat(99L, "private")); Update update = new Update();
        if (callback) { CallbackQuery query = new CallbackQuery(); query.setMessage(message); query.setData(value); update.setCallbackQuery(query); }
        else { message.setText(value); update.setMessage(message); } return update;
    }
}
