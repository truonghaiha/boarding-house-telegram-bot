package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.ContractService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomContractDisplayTest {
    final ContractService contracts = mock(ContractService.class);
    final BotMessageSender sender = mock(BotMessageSender.class);
    final ContractCommandHandler handler = new ContractCommandHandler(contracts, mock(UserService.class), new ConversationStateService());
    final User representative = new User(8962501228L, "hoang", "Thái Hoàng", Role.MEMBER);
    final User peer = new User(11L, "an", "An", Role.MEMBER);
    final User landlord = new User(99L, "owner", "Chủ trọ", Role.LANDLORD);
    Contract contract;

    @BeforeEach void setup() {
        Room room = new Room("105", new BigDecimal("6000000"), 3, "");
        room.setId(1L);
        contract = new Contract(representative, room, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 11, 14),
                new BigDecimal("6000000"), new BigDecimal("3000000"));
        contract.setId(72L);
        when(contracts.getCurrentRoomActiveContract(anyLong())).thenReturn(Optional.of(contract));
        when(contracts.getContractForUser(any(), eq(72L))).thenReturn(contract);
    }

    @Test void representativeAndCoTenantSeeSameRoomContractWithoutIdsOrManagementActions() {
        String first = memberScreen(representative, "MEMBER_CONTRACT");
        String second = memberScreen(peer, "MEMBER_CONTRACT");
        assertEquals(first, second);
        assertTrue(first.startsWith("📄 HỢP ĐỒNG PHÒNG 105"));
        assertTrue(first.contains("Người đại diện: Thái Hoàng"));
        assertTrue(first.contains("14/10/2026"));
        assertTrue(first.contains("14/11/2026"));
        assertTrue(first.contains("Tiền cọc:"));
        assertFalse(first.contains("Telegram ID"));
        assertFalse(first.contains("8962501228"));
        assertFalse(first.contains("#72"));
    }

    @Test void directMemberDetailAlsoHidesIds() {
        assertEquals(memberScreen(peer, "MEMBER_CONTRACT"), memberScreen(peer, "CONTRACT_DETAIL_72"));
        verify(contracts).getContractForUser(peer, 72L);
    }

    @Test void landlordDetailKeepsIdsAndManagementButtonsButUsesRepresentativeLabel() {
        handler.handle(update("CONTRACT_DETAIL_72"), landlord, sender);
        verify(sender).sendMessageWithKeyboard(eq(99L), argThat(text -> text.contains("Người đại diện: Thái Hoàng")
                && text.contains("Telegram ID: 8962501228") && text.contains("#72") && !text.contains("Người thuê:")),
                argThat(keyboard -> keyboard.getKeyboard().stream().flatMap(java.util.List::stream)
                        .anyMatch(button -> "CONTRACT_EDIT_72".equals(button.getCallbackData()))));
    }

    private String memberScreen(User user, String action) {
        clearInvocations(sender);
        handler.handle(update(action), user, sender);
        var text = ArgumentCaptor.forClass(String.class);
        var keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        verify(sender).sendMessageWithKeyboard(eq(99L), text.capture(), keyboard.capture());
        assertFalse(keyboard.getValue().getKeyboard().stream().flatMap(java.util.List::stream)
                .anyMatch(button -> button.getCallbackData().startsWith("CONTRACT_EDIT_") || button.getCallbackData().startsWith("CONTRACT_END_")));
        return text.getValue();
    }
    private Update update(String action) {
        Message message = new Message(); message.setChat(new Chat(99L, "private"));
        CallbackQuery query = new CallbackQuery(); query.setData(action); query.setMessage(message);
        Update update = new Update(); update.setCallbackQuery(query); return update;
    }
}
