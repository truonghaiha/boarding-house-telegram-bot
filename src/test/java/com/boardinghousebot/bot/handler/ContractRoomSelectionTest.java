package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.ContractService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState.Step;
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

class ContractRoomSelectionTest {
    private final ContractService contracts = mock(ContractService.class);
    private final UserService users = mock(UserService.class);
    private final ConversationStateService states = new ConversationStateService();
    private final BotMessageSender sender = mock(BotMessageSender.class);
    private final ContractCommandHandler handler = new ContractCommandHandler(contracts, users, states);
    private final User landlord = member(99L, "Chủ trọ", null);
    private final Room room101 = room(1L, "101");
    private final Room room102 = room(2L, "102");
    private final User first = member(11L, "Hoàng", room101);
    private final User second = member(12L, "An", room101);
    private final User other = member(13L, "Bình", room102);

    @BeforeEach
    void setUp() {
        landlord.setRole(Role.LANDLORD);
        when(users.getUsersByRoleWithRoom(Role.MEMBER)).thenReturn(List.of(first, second, other));
        when(users.findByTelegramIdWithRoom(11L)).thenReturn(Optional.of(first));
        when(users.findByTelegramIdWithRoom(13L)).thenReturn(Optional.of(other));
        when(contracts.getMemberActiveContract(anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void listsEachRoomOnceAndExcludesRoomsWithActiveContracts() {
        when(contracts.hasActiveContractForRoom(2L)).thenReturn(true);
        click("CONTRACT_ADD");
        assertEquals(List.of("CONTRACT_SELECT_ROOM_1", "CANCEL_CREATE_CONTRACT"), buttons());
        assertEquals(Step.CREATE_CONTRACT_ROOM_SELECT, states.getState(99L).getStep());
    }

    @Test
    void selectedRoomListsBothResidentsAndSelectionContinuesToDates() {
        click("CONTRACT_ADD");
        clearInvocations(sender);
        click("CONTRACT_SELECT_ROOM_1");
        assertEquals(List.of("CONTRACT_SELECT_MEMBER_11", "CONTRACT_SELECT_MEMBER_12", "CANCEL_CREATE_CONTRACT"), buttons());
        assertEquals(1L, states.getState(99L).getTargetRoomId());
        click("CONTRACT_SELECT_MEMBER_11");
        assertEquals(Step.CREATE_CONTRACT_START_DATE, states.getState(99L).getStep());
        assertEquals(11L, states.getState(99L).getTargetUserTelegramId());
    }

    @Test
    void staleRoomButtonCannotSelectRoomThatNowHasContract() {
        click("CONTRACT_ADD");
        when(contracts.hasActiveContractForRoom(1L)).thenReturn(true);
        clearInvocations(sender);
        click("CONTRACT_SELECT_ROOM_1");
        assertEquals(List.of("CONTRACT_SELECT_ROOM_2", "CANCEL_CREATE_CONTRACT"), buttons());
        assertEquals(Step.CREATE_CONTRACT_ROOM_SELECT, states.getState(99L).getStep());
    }

    @Test
    void cannotSelectResidentFromAnotherRoomOrSkipRoomSelection() {
        click("CONTRACT_SELECT_MEMBER_11");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        click("CONTRACT_ADD");
        click("CONTRACT_SELECT_ROOM_1");
        click("CONTRACT_SELECT_MEMBER_13");
        assertEquals(Step.CREATE_CONTRACT_ROOM_SELECT, states.getState(99L).getStep());
        assertNull(states.getState(99L).getTargetUserTelegramId());
    }

    @Test
    void emptyListAndCancellationLeaveNoActiveWizard() {
        when(contracts.hasActiveContractForRoom(anyLong())).thenReturn(true);
        click("CONTRACT_ADD");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        when(contracts.hasActiveContractForRoom(anyLong())).thenReturn(false);
        click("CONTRACT_ADD");
        click("CANCEL_CREATE_CONTRACT");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        click("CONTRACT_ADD");
        click("CONTRACT_SELECT_ROOM_1");
        click("CANCEL_CREATE_CONTRACT");
        assertEquals(Step.NONE, states.getState(99L).getStep());
    }

    @Test
    void confirmationPassesSelectedRoomToService() {
        click("CONTRACT_ADD");
        click("CONTRACT_SELECT_ROOM_1");
        click("CONTRACT_SELECT_MEMBER_11");
        var state = states.getState(99L);
        state.setStep(Step.CREATE_CONTRACT_CONFIRM);
        when(contracts.createContract(eq(99L), eq(11L), any(), any(), any(), any(), eq(1L)))
                .thenThrow(new com.boardinghousebot.exception.BusinessException("Phòng đã có hợp đồng đang hiệu lực."));
        click("CONFIRM_CREATE_CONTRACT");
        verify(contracts).createContract(99L, 11L, null, null, null, null, 1L);
        assertEquals(Step.NONE, states.getState(99L).getStep());
    }

    private void startPriceSelection(BigDecimal deposit) {
        room101.setRequiredDeposit(deposit);
        var state = new com.boardinghousebot.state.UserConversationState(Step.CREATE_CONTRACT_END_DATE);
        state.setTargetRoomId(1L); state.setTargetUserTelegramId(11L);
        state.getData().put("startDate", java.time.LocalDate.of(2026, 10, 1));
        states.setState(99L, state);
        text("01/10/2027");
    }

    @Test
    void listedRentAndDepositAreExplicitChoicesAndAdvanceOneStepAtATime() {
        startPriceSelection(new BigDecimal("1500000"));
        assertEquals(Step.CREATE_CONTRACT_RENT, states.getState(99L).getStep());
        assertTrue(promptButtons().contains("CONTRACT_USE_RENT_1"));
        clearInvocations(sender);
        click("CONTRACT_USE_RENT_1");
        assertEquals(Step.CREATE_CONTRACT_DEPOSIT, states.getState(99L).getStep());
        assertEquals(new BigDecimal("4000000"), states.getState(99L).getData().get("rent"));
        assertFalse(states.getState(99L).getData().containsKey("deposit"));
        assertTrue(promptButtons().contains("CONTRACT_USE_DEPOSIT_1"));
        click("CONTRACT_USE_DEPOSIT_1");
        assertEquals(Step.CREATE_CONTRACT_CONFIRM, states.getState(99L).getStep());
        assertEquals(new BigDecimal("1500000"), states.getState(99L).getData().get("deposit"));
        verify(sender).sendMessageWithKeyboard(eq(99L), contains("XÁC NHẬN"), any());
        verifyNoInteractions(contracts);
    }

    @Test
    void customAmountsAreShownWithListedAmountsAndDoNotChangeRoom() {
        startPriceSelection(new BigDecimal("1500000"));
        text("3500000"); text("1000000");
        var preview = ArgumentCaptor.forClass(String.class);
        verify(sender).sendMessageWithKeyboard(eq(99L), preview.capture(), any());
        assertTrue(preview.getValue().contains("Giá thuê niêm yết:"));
        assertTrue(preview.getValue().contains("Tiền cọc yêu cầu của phòng:"));
        assertTrue(preview.getValue().contains("chỉ áp dụng cho hợp đồng này"));
        assertEquals(new BigDecimal("3500000"), states.getState(99L).getData().get("rent"));
        assertEquals(new BigDecimal("1000000"), states.getState(99L).getData().get("deposit"));
        assertEquals(new BigDecimal("4000000"), room101.getMonthlyRent());
        assertEquals(new BigDecimal("1500000"), room101.getRequiredDeposit());
    }

    @Test
    void missingDepositRequiresManualInputAndZeroDepositCanBeSelected() {
        startPriceSelection(null); text("4000000");
        assertEquals(List.of("CANCEL_CREATE_CONTRACT"), lastPromptButtons());
        text("0");
        assertEquals(Step.CREATE_CONTRACT_CONFIRM, states.getState(99L).getStep());
        clearInvocations(sender);
        startPriceSelection(BigDecimal.ZERO); click("CONTRACT_USE_RENT_1");
        assertTrue(lastPromptButtons().contains("CONTRACT_USE_DEPOSIT_1"));
        click("CONTRACT_USE_DEPOSIT_1");
        assertEquals(BigDecimal.ZERO, states.getState(99L).getData().get("deposit"));
    }

    @Test
    void invalidAmountsAndRepeatedOrWrongRoomButtonsCannotAdvanceOrOverwriteChoices() {
        startPriceSelection(new BigDecimal("1500000"));
        for (String value : List.of("0", "-1", "abc")) text(value);
        assertEquals(Step.CREATE_CONTRACT_RENT, states.getState(99L).getStep());
        click("CONTRACT_USE_RENT_2");
        assertEquals(Step.CREATE_CONTRACT_RENT, states.getState(99L).getStep());
        click("CONTRACT_USE_RENT_1");
        click("CONTRACT_USE_RENT_1");
        assertEquals(Step.CREATE_CONTRACT_DEPOSIT, states.getState(99L).getStep());
        text("-1"); text("abc");
        assertEquals(Step.CREATE_CONTRACT_DEPOSIT, states.getState(99L).getStep());
        text("0"); click("CONTRACT_USE_DEPOSIT_1");
        assertEquals(Step.CREATE_CONTRACT_CONFIRM, states.getState(99L).getStep());
        assertEquals(BigDecimal.ZERO, states.getState(99L).getData().get("deposit"));
        verifyNoInteractions(contracts);
    }

    @Test
    void changedListedPriceIsRedisplayedAndRequiresAnotherClick() {
        startPriceSelection(new BigDecimal("1500000"));
        room101.setMonthlyRent(new BigDecimal("4500000"));
        click("CONTRACT_USE_RENT_1");
        assertEquals(Step.CREATE_CONTRACT_RENT, states.getState(99L).getStep());
        assertFalse(states.getState(99L).getData().containsKey("rent"));
        verify(sender).sendNotice(eq(99L), contains("đã thay đổi"));
        click("CONTRACT_USE_RENT_1");
        assertEquals(new BigDecimal("4500000"), states.getState(99L).getData().get("rent"));
        room101.setRequiredDeposit(new BigDecimal("2000000"));
        click("CONTRACT_USE_DEPOSIT_1");
        assertEquals(Step.CREATE_CONTRACT_DEPOSIT, states.getState(99L).getStep());
        click("CONTRACT_USE_DEPOSIT_1");
        assertEquals(new BigDecimal("2000000"), states.getState(99L).getData().get("deposit"));
    }

    @Test
    void priceButtonsAreDeniedToMembersAndCannotBeUsedAfterCancellation() {
        startPriceSelection(new BigDecimal("1500000"));
        click("CANCEL_CREATE_CONTRACT"); click("CONTRACT_USE_RENT_1");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        landlord.setRole(Role.MEMBER);
        clearInvocations(users);
        click("CONTRACT_USE_RENT_1");
        verify(sender).sendNotice(eq(99L), contains("không có quyền"));
        verifyNoInteractions(users, contracts);
    }

    private void startUpdatePriceSelection(BigDecimal deposit) {
        room101.setRequiredDeposit(deposit);
        var contract = new com.boardinghousebot.domain.contract.Contract(first, room101,
                java.time.LocalDate.of(2026,10,20), java.time.LocalDate.of(2027,10,20),
                new BigDecimal("3000000"), BigDecimal.ZERO);
        contract.setId(17L);
        when(contracts.getContractById(17L)).thenReturn(contract);
        click("CONTRACT_EDIT_17");
        text("21/10/2026");
        text("21/11/2026");
    }

    @Test
    void editUsesListedRentAndDepositFromContractRoomAndSavesOnlyOnConfirmation() {
        startUpdatePriceSelection(new BigDecimal("2500000"));
        assertEquals(List.of("CONTRACT_USE_RENT_1", "CANCEL_UPDATE_CONTRACT"), lastPromptButtons());
        first.setRoom(room102);
        click("CONTRACT_USE_RENT_2");
        assertEquals(Step.UPDATE_CONTRACT_RENT, states.getState(99L).getStep());
        click("CONTRACT_USE_RENT_1");
        assertEquals(List.of("CONTRACT_USE_DEPOSIT_1", "CANCEL_UPDATE_CONTRACT"), lastPromptButtons());
        click("CONTRACT_USE_DEPOSIT_1");
        var state = states.getState(99L);
        assertEquals(Step.UPDATE_CONTRACT_CONFIRM, state.getStep());
        assertEquals(room101.getMonthlyRent(), state.getData().get("rent"));
        assertEquals(new BigDecimal("2500000"), state.getData().get("deposit"));
        verify(contracts, never()).updateContract(anyLong(), anyLong(), any(), any(), any(), any());
        var updated = contracts.getContractById(17L);
        when(contracts.updateContract(anyLong(), anyLong(), any(), any(), any(), any())).thenReturn(updated);
        click("CONFIRM_UPDATE_CONTRACT");
        verify(contracts).updateContract(99L, 17L, java.time.LocalDate.of(2026,10,21),
                java.time.LocalDate.of(2026,11,21), room101.getMonthlyRent(), new BigDecimal("2500000"));
    }

    @Test
    void editRefreshesChangedListedPriceAndRejectsButtonsAfterCancellation() {
        startUpdatePriceSelection(BigDecimal.ZERO);
        room101.setMonthlyRent(new BigDecimal("4500000"));
        click("CONTRACT_USE_RENT_1");
        assertEquals(Step.UPDATE_CONTRACT_RENT, states.getState(99L).getStep());
        assertFalse(states.getState(99L).getData().containsKey("rent"));
        click("CONTRACT_USE_RENT_1");
        click("CONTRACT_USE_DEPOSIT_1");
        assertEquals(BigDecimal.ZERO, states.getState(99L).getData().get("deposit"));
        click("CANCEL_UPDATE_CONTRACT");
        click("CONTRACT_USE_RENT_1");
        assertEquals(Step.NONE, states.getState(99L).getStep());
        verify(contracts, never()).updateContract(anyLong(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void editAllowsCustomAmountsWhenRoomDepositIsMissing() {
        startUpdatePriceSelection(null);
        text("3500000");
        assertEquals(List.of("CANCEL_UPDATE_CONTRACT"), lastPromptButtons());
        text("1000000");
        assertEquals(Step.UPDATE_CONTRACT_CONFIRM, states.getState(99L).getStep());
        assertEquals(new BigDecimal("3500000"), states.getState(99L).getData().get("rent"));
        assertEquals(new BigDecimal("1000000"), states.getState(99L).getData().get("deposit"));
    }

    private List<String> promptButtons() { return lastPromptButtons(); }

    private List<String> lastPromptButtons() {
        var captor = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender, atLeastOnce()).sendPrompt(eq(99L), anyString(), captor.capture());
        return captor.getValue().getKeyboard().stream().flatMap(List::stream)
                .map(button -> button.getCallbackData()).toList();
    }

    private void text(String value) {
        Message message = new Message(); message.setChat(new Chat(99L, "private")); message.setText(value);
        Update update = new Update(); update.setMessage(message);
        handler.handle(update, landlord, sender);
    }

    private List<String> buttons() {
        var captor = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendMessageWithKeyboard(eq(99L), anyString(), captor.capture());
        return captor.getValue().getKeyboard().stream().flatMap(List::stream)
                .map(button -> button.getCallbackData()).toList();
    }

    private void click(String data) {
        Message message = new Message();
        message.setChat(new Chat(99L, "private"));
        message.setMessageId(1);
        CallbackQuery query = new CallbackQuery();
        query.setData(data);
        query.setMessage(message);
        Update update = new Update();
        update.setCallbackQuery(query);
        handler.handle(update, landlord, sender);
    }

    private static Room room(Long id, String number) {
        Room room = new Room(number, new BigDecimal("4000000"), 2, "");
        room.setId(id);
        return room;
    }

    private static User member(Long id, String name, Room room) {
        User user = new User();
        user.setTelegramId(id);
        user.setFullName(name);
        user.setRole(Role.MEMBER);
        user.setRoom(room);
        return user;
    }
}
