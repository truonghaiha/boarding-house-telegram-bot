package com.boardinghousebot.bot;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.bot.command.CommandHandler;
import com.boardinghousebot.bot.handler.ContractCommandHandler;
import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.ContractService;
import com.boardinghousebot.service.RoomStayService;
import com.boardinghousebot.service.UserService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.util.TelegramKeyboardUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.*;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractListNavigationTest {
    final ContractService contracts = mock(ContractService.class);
    final UserService users = mock(UserService.class);
    final RoomStayService stays = mock(RoomStayService.class);
    final TelegramClient client = mock(TelegramClient.class);
    final User owner = new User(99L, "owner", "Chủ trọ", Role.LANDLORD);
    final User representative = new User(11L, "tenant", "Thái Hoàng", Role.MEMBER);
    final AtomicInteger messageIds = new AtomicInteger(100);
    List<Contract> leases;
    BoardingHouseTelegramBot bot;

    @BeforeEach void setup() throws Exception {
        Room room = new Room("105", new BigDecimal("6000000"), 3, "");
        room.setId(1L);
        leases = List.of(lease(81L, room, ContractStatus.ACTIVE), lease(92L, room, ContractStatus.ENDED), lease(103L, room, ContractStatus.ENDED));
        when(contracts.getAllContracts()).thenReturn(leases);
        when(contracts.getActiveContracts()).thenReturn(leases);
        when(contracts.getEndedContracts()).thenReturn(leases);
        when(contracts.getExpiringContracts(30)).thenReturn(leases);
        for (Contract lease : leases) when(contracts.getContractForUser(any(), eq(lease.getId()))).thenReturn(lease);
        when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(owner);
        when(stays.allowsMemberAction(any(), anyString(), anyString(), any())).thenReturn(true);
        when(client.execute(any(SendMessage.class))).thenAnswer(call -> {
            Message sent = new Message(); sent.setMessageId(messageIds.incrementAndGet()); return sent;
        });
        CommandHandler root = mock(CommandHandler.class), menu = mock(CommandHandler.class);
        when(root.getCommand()).thenReturn("/start"); when(menu.getCommand()).thenReturn("/menu");
        for (CommandHandler handler : List.of(root, menu)) doAnswer(call -> {
            BotMessageSender sender = call.getArgument(2);
            sender.sendMessageWithKeyboard(99L, "Menu chính", TelegramKeyboardUtil.getLandlordMenu()); return null;
        }).when(handler).handle(any(), any(), any());
        bot = new BoardingHouseTelegramBot(new TelegramConfig(), users, new ConversationStateService(), stays,
                List.of(root, menu, new ContractCommandHandler(contracts, users, new ConversationStateService())));
        ReflectionTestUtils.setField(bot, "telegramClient", client);
    }

    @Test void managementMenuHasFiveRequestedRows() {
        var rows = TelegramKeyboardUtil.getLandlordContractManagementMenu().getKeyboard();
        assertEquals(List.of(1, 1, 2, 1, 1), rows.stream().map(List::size).toList());
        assertEquals(List.of("➕ Tạo hợp đồng", "📋 Danh sách tất cả hợp đồng", "🟢 Đang hiệu lực", "⚫ Đã kết thúc", "⏰ Sắp hết hạn", "⬅️ Menu chính"),
                rows.stream().flatMap(List::stream).map(b -> b.getText()).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTRACT_LIST_ALL", "CONTRACT_LIST_ACTIVE", "CONTRACT_LIST_ENDED", "CONTRACT_LIST_EXPIRING"})
    void allListsUseSameTemplateAndButtonsDistinguishSameRoomAndSamePerson(String action) throws Exception {
        click(action);
        SendMessage screen = last();
        assertFalse(screen.getText().contains("/contract_detail_"));
        assertTrue(screen.getText().contains("HĐ #81 · 🏠 Phòng 105"));
        assertTrue(screen.getText().contains("HĐ #92 · 🏠 Phòng 105"));
        assertTrue(screen.getText().contains("Thái Hoàng"));
        assertTrue(screen.getText().contains("14/10/2026 → 14/11/2026"));
        assertTrue(screen.getText().contains("Đang hiệu lực"));
        assertTrue(screen.getText().contains("Đã kết thúc"));
        var keyboard = (InlineKeyboardMarkup) screen.getReplyMarkup();
        var choices = keyboard.getKeyboard().stream().filter(row -> row.stream().allMatch(b -> b.getCallbackData().startsWith("CONTRACT_DETAIL_"))).toList();
        assertEquals(List.of(2, 1), choices.stream().map(List::size).toList());
        assertEquals(List.of("CONTRACT_DETAIL_81", "CONTRACT_DETAIL_92", "CONTRACT_DETAIL_103"), choices.stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList());
        assertEquals("📄 HĐ #92 – P105", choices.get(0).get(1).getText());
        assertTrue(actions(screen).contains("MAIN_MENU"));
        click("CONTRACT_DETAIL_92");
        verify(contracts).getContractForUser(any(), eq(92L));
        assertTrue(last().getText().contains("CHI TIẾT HỢP ĐỒNG #92"));
        verifyNoEdits();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTRACT_LIST_ALL", "CONTRACT_LIST_ACTIVE", "CONTRACT_LIST_ENDED", "CONTRACT_LIST_EXPIRING"})
    void detailBackReturnsToSourceFilterAndRereadsData(String action) throws Exception {
        click("/start"); click("LANDLORD_CONTRACTS"); click(action);
        String listText = last().getText();
        click("CONTRACT_DETAIL_81");
        assertTrue(actions(last()).contains("UI_BACK"));
        var detailButtons = ((InlineKeyboardMarkup) last().getReplyMarkup()).getKeyboard().stream().flatMap(List::stream).toList();
        assertTrue(detailButtons.stream().anyMatch(b -> "🔙 Quay lại".equals(b.getText()) && "UI_BACK".equals(b.getCallbackData())));
        assertFalse(detailButtons.stream().anyMatch(b -> "📋 Danh sách hợp đồng".equals(b.getText())));
        var detailRows = ((InlineKeyboardMarkup) last().getReplyMarkup()).getKeyboard();
        var navigation = detailRows.get(detailRows.size() - 1);
        assertEquals(List.of("UI_BACK", "MAIN_MENU"), navigation.stream().map(b -> b.getCallbackData()).toList());
        click("UI_BACK");
        assertEquals(listText, last().getText());
        switch (action) {
            case "CONTRACT_LIST_ALL" -> verify(contracts, times(2)).getAllContracts();
            case "CONTRACT_LIST_ACTIVE" -> verify(contracts, times(2)).getActiveContracts();
            case "CONTRACT_LIST_ENDED" -> verify(contracts, times(2)).getEndedContracts();
            default -> verify(contracts, times(2)).getExpiringContracts(30);
        }
        click("MAIN_MENU"); assertEquals("Menu chính", last().getText());
        verifyNoEdits();
    }

    @Test void detailClickedFromOldListKeepsThatListsReturnFilter() throws Exception {
        click("/start"); click("LANDLORD_CONTRACTS"); click("CONTRACT_LIST_ACTIVE");
        int oldListId = messageIds.get();
        click("CONTRACT_LIST_ENDED");
        bot.consume(update("CONTRACT_DETAIL_81", oldListId));
        click("UI_BACK");
        assertTrue(last().getText().startsWith("🟢 HỢP ĐỒNG ĐANG HIỆU LỰC"));
        verify(contracts, times(2)).getActiveContracts();
        verifyNoEdits();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONTRACT_LIST_ALL", "CONTRACT_LIST_ACTIVE", "CONTRACT_LIST_ENDED", "CONTRACT_LIST_EXPIRING"})
    void emptyListsKeepBackAndMainMenu(String action) throws Exception {
        when(contracts.getAllContracts()).thenReturn(List.of()); when(contracts.getActiveContracts()).thenReturn(List.of());
        when(contracts.getEndedContracts()).thenReturn(List.of()); when(contracts.getExpiringContracts(30)).thenReturn(List.of());
        click(action);
        assertTrue(last().getText().contains("Không có hợp đồng nào phù hợp"));
        assertTrue(actions(last()).contains("LANDLORD_CONTRACTS"));
        assertTrue(actions(last()).contains("MAIN_MENU"));
        click("LANDLORD_CONTRACTS"); assertTrue(last().getText().contains("QUẢN LÝ HỢP ĐỒNG"));
    }

    @Test void memberAndAdminCannotOpenLandlordLists() throws Exception {
        for (Role role : List.of(Role.MEMBER, Role.ADMIN)) {
            when(users.getOrCreateUser(anyLong(), any(), any())).thenReturn(new User(88L, "other", "Other", role));
            click("CONTRACT_LIST_ALL");
            assertTrue(last().getText().contains("không có quyền"));
        }
        verify(contracts, never()).getAllContracts();
    }

    @Test void legacyTextDetailStillResolvesActualContractId() throws Exception {
        Message message = new Message(); message.setChat(new Chat(99L, "private"));
        message.setFrom(new org.telegram.telegrambots.meta.api.objects.User(99L, "Owner", false));
        message.setText("/contract_detail_103"); Update update = new Update(); update.setMessage(message); bot.consume(update);
        verify(contracts).getContractForUser(any(), eq(103L));
        assertTrue(last().getText().contains("CHI TIẾT HỢP ĐỒNG #103"));
        var buttons = ((InlineKeyboardMarkup) last().getReplyMarkup()).getKeyboard().stream().flatMap(List::stream).toList();
        assertTrue(buttons.stream().anyMatch(b -> "🔙 Quay lại".equals(b.getText()) && "CONTRACT_LIST_ALL".equals(b.getCallbackData())));
    }

    Contract lease(Long id, Room room, ContractStatus status) {
        Contract lease = new Contract(representative, room, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 11, 14), new BigDecimal("6000000"), new BigDecimal("3000000"));
        lease.setId(id); lease.setStatus(status); return lease;
    }
    void click(String action) { bot.consume(update(action, messageIds.get())); }
    Update update(String action, int messageId) {
        Message message = new Message(); message.setChat(new Chat(99L, "private")); message.setMessageId(messageId);
        CallbackQuery query = new CallbackQuery(); query.setData(action); query.setMessage(message); query.setId("callback" + messageId);
        query.setFrom(new org.telegram.telegrambots.meta.api.objects.User(99L, "Owner", false));
        Update update = new Update(); update.setCallbackQuery(query); return update;
    }
    SendMessage last() throws Exception {
        var captor = ArgumentCaptor.forClass(SendMessage.class); verify(client, atLeastOnce()).execute(captor.capture()); return captor.getValue();
    }
    List<String> actions(SendMessage message) {
        return ((InlineKeyboardMarkup) message.getReplyMarkup()).getKeyboard().stream().flatMap(List::stream).map(b -> b.getCallbackData()).toList();
    }
    void verifyNoEdits() throws Exception {
        verify(client, never()).execute(any(EditMessageText.class)); verify(client, never()).execute(any(EditMessageReplyMarkup.class));
        verify(client, never()).execute(any(DeleteMessage.class));
    }
}
