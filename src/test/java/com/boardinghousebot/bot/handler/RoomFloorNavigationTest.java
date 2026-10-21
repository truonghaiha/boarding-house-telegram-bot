package com.boardinghousebot.bot.handler;

import com.boardinghousebot.bot.command.BotMessageSender;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.service.RoomService;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomFloorNavigationTest {
    private final RoomService service = mock(RoomService.class);
    private final BotMessageSender sender = mock(BotMessageSender.class);
    private final ConversationStateService states = new ConversationStateService();
    private final RoomCommandHandler handler = new RoomCommandHandler(service, states);
    private final User landlord = new User(123L, "landlord", "Landlord", Role.LANDLORD);
    private final Room first = room(1, "101");
    private final Room second = room(2, "102");
    private final Room upstairs = room(3, "201");

    @BeforeEach
    void setUp() {
        when(service.getAllRooms()).thenReturn(List.of(upstairs, second, first));
        when(service.getRoomById(1L)).thenReturn(first);
        when(service.getRoomById(3L)).thenReturn(upstairs);
        when(service.getRoomTenants(anyLong())).thenReturn(List.of());
    }

    @Test
    void listShowsOnlyExistingFloorsInNumericOrderWithCounts() {
        when(service.getAllRooms()).thenReturn(List.of(room(4, "1001"), upstairs, second, first));
        Screen screen = screen("ROOM_LIST_ALL");
        assertTrue(screen.text().contains("Tầng 1: 2 phòng"));
        assertTrue(screen.text().contains("Tầng 2: 1 phòng"));
        assertTrue(screen.text().contains("Tầng 10: 1 phòng"));
        assertFalse(screen.text().contains("Tầng 3"));
        assertEquals(List.of("ROOM_FLOOR_1_0", "ROOM_FLOOR_2_0", "ROOM_FLOOR_10_0", "LANDLORD_ROOMS"), screen.callbacks());
        verify(service).getCurrentOccupantCount(1L);
    }

    @Test
    void selectedFloorShowsOnlyItsRoomsAndOccupancyStatus() {
        when(service.getCurrentOccupantCount(1L)).thenReturn(0);
        when(service.getCurrentOccupantCount(2L)).thenReturn(5);
        Screen screen = screen("ROOM_FLOOR_1_0");
        assertTrue(screen.text().contains("Tầng 1 (2 phòng)"));
        assertTrue(screen.text().contains("Phòng 101 · ⚪ Trống\n  💰 5,000,000 VNĐ · 👥 0/5 người"));
        assertTrue(screen.text().contains("Phòng 102 · 🔴 Đã đầy\n  💰 5,000,000 VNĐ · 👥 5/5 người"));
        assertTrue(screen.text().contains("5/5 người\n\n👇 Chọn phòng để xem chi tiết."));
        assertFalse(screen.text().contains("Phòng 201"));
        assertEquals(List.of("ROOM_FLOOR_1_0_ALL", "ROOM_FLOOR_1_0_AVAILABLE", "ROOM_FLOOR_1_0_FULL",
                "ROOM_DETAIL_1_F_1_0", "ROOM_DETAIL_2_F_1_0", "ROOM_FLOORS_0"), screen.callbacks());
        verify(service, never()).getCurrentOccupantCount(3L);
    }

    @Test
    void selectedFloorGroupsRoomButtonsTwoPerRowAndKeepsOddRoomOnLastRow() {
        List<Room> rooms = IntStream.rangeClosed(1, 5)
                .mapToObj(n -> room(n, Integer.toString(100 + n)))
                .toList();
        when(service.getAllRooms()).thenReturn(rooms);

        Screen screen = screen("ROOM_FLOOR_1_0");
        List<InlineKeyboardRow> roomRows = screen.keyboard().getKeyboard().stream()
                .filter(row -> row.stream().allMatch(button -> button.getCallbackData().startsWith("ROOM_DETAIL_")))
                .toList();

        assertEquals(List.of(2, 2, 1), roomRows.stream().map(List::size).toList());
        assertEquals(List.of("ROOM_DETAIL_1_F_1_0", "ROOM_DETAIL_2_F_1_0"),
                roomRows.get(0).stream().map(InlineKeyboardButton::getCallbackData).toList());
        assertEquals("ROOM_DETAIL_5_F_1_0", roomRows.get(2).get(0).getCallbackData());
    }

    @Test
    void detailPreservesInformationAndActionsAndReturnsToSelectedFloor() {
        User tenant = new User(456L, "tenant", "Nguyễn Văn A", Role.MEMBER);
        when(service.getRoomTenants(1L)).thenReturn(List.of(tenant));
        when(service.getCurrentOccupantCount(1L)).thenReturn(1);
        Screen screen = screen("ROOM_DETAIL_1_F_1_0");
        assertTrue(screen.text().startsWith("🏠 CHI TIẾT PHÒNG 101"));
        assertTrue(screen.text().contains("Giá thuê: 5,000,000 VNĐ"));
        assertTrue(screen.text().contains("Số người: 1/5 người"));
        assertTrue(screen.text().contains("Trạng thái:"));
        assertTrue(screen.text().contains("Nguyễn Văn A (ID: 456)"));
        assertTrue(screen.text().contains("Mô tả: phòng đẹp"));
        assertTrue(screen.text().contains("Nguyễn Văn A (ID: 456)\n- Số điện thoại: Chưa cập nhật\n\n📝 Mô tả: phòng đẹp"));
        assertEquals(List.of("ROOM_TENANTS_1", "ROOM_MOVE_1", "ROOM_ASSIGN_1", "ROOM_REMOVE_TENANTS_1", "ROOM_EDIT_RENT_1", "ROOM_EDIT_DESC_1", "ROOM_EDIT_AREA_1", "ROOM_EDIT_DEPOSIT_1", "ROOM_DELETE_1", "ROOM_FLOOR_1_0"), screen.callbacks());
        assertEquals("🔙 Danh sách phòng", screen.buttons().get(9).getText());
        assertTrue(screen("ROOM_FLOOR_1_0").text().contains("Tầng 1 (2 phòng)"));
        assertTrue(screen("ROOM_FLOORS_0").text().contains("DANH SÁCH TẦNG"));
        Screen management = screen("LANDLORD_ROOMS");
        assertTrue(management.text().contains("Tổng số phòng: 3"));
        assertTrue(management.text().contains("Tổng số tầng: 2"));
        assertEquals(List.of("ROOM_ADD", "ROOM_LIST_ALL", "MAIN_MENU"), management.callbacks());
    }

    @Test
    void separateMessagesDoNotOverwriteEachOthersReturnFloor() {
        screen("ROOM_FLOOR_1_0");
        screen("ROOM_FLOOR_2_0");
        assertTrue(screen("ROOM_DETAIL_1_F_1_0").callbacks().contains("ROOM_FLOOR_1_0"));
        assertTrue(screen("ROOM_DETAIL_3_F_2_0").callbacks().contains("ROOM_FLOOR_2_0"));
    }

    @Test
    void legacyDetailCallbackStillWorksAndDerivesReturnFloor() {
        assertTrue(screen("ROOM_DETAIL_3").callbacks().contains("ROOM_FLOOR_2_0"));
    }

    @Test
    void floorsArePaginatedWithoutAnUpperFloorLimit() {
        List<Room> rooms = IntStream.rangeClosed(1, 25).mapToObj(floor -> room(floor, floor + "01")).toList();
        when(service.getAllRooms()).thenReturn(rooms);
        Screen firstPage = screen("ROOM_LIST_ALL");
        assertTrue(firstPage.text().contains("Trang 1/3"));
        assertTrue(firstPage.callbacks().contains("ROOM_FLOORS_1"));
        Screen lastPage = screen("ROOM_FLOORS_2");
        assertTrue(lastPage.text().contains("Tầng 25: 1 phòng"));
        assertTrue(lastPage.text().contains("Trang 3/3"));
        assertFalse(lastPage.callbacks().contains("ROOM_FLOORS_3"));
        Screen floor = screen("ROOM_FLOOR_25_0");
        assertTrue(floor.callbacks().contains("ROOM_FLOORS_2"));
    }

    @Test
    void roomPagesAndReturnPageArePreserved() {
        List<Room> rooms = IntStream.rangeClosed(1, 21).mapToObj(n -> room(n, Integer.toString(100 + n))).toList();
        when(service.getAllRooms()).thenReturn(rooms);
        when(service.getRoomById(21L)).thenReturn(rooms.get(20));
        Screen lastPage = screen("ROOM_FLOOR_1_2");
        assertTrue(lastPage.text().contains("Phòng 121"));
        assertFalse(lastPage.text().contains("Phòng 101"));
        assertTrue(lastPage.callbacks().contains("ROOM_DETAIL_21_F_1_2"));
        assertTrue(lastPage.callbacks().contains("ROOM_FLOOR_1_1"));
        assertTrue(screen("ROOM_DETAIL_21_F_1_2").callbacks().contains("ROOM_FLOOR_1_2"));
    }

    @Test
    void emptyDatabaseAndDeletedFloorHaveWorkingBackNavigation() {
        when(service.getAllRooms()).thenReturn(List.of());
        Screen empty = screen("ROOM_LIST_ALL");
        assertTrue(empty.text().contains("Chưa có phòng nào"));
        assertEquals(List.of("LANDLORD_ROOMS"), empty.callbacks());
        assertTrue(screen("ROOM_FLOOR_1_0").text().contains("DANH SÁCH TẦNG"));
    }

    @Test
    void legacyNonNumericRoomsRemainAccessible() {
        Room legacy = room(4, "A01");
        when(service.getAllRooms()).thenReturn(List.of(first, legacy));
        assertTrue(screen("ROOM_LIST_ALL").text().contains("Chưa xác định tầng: 1 phòng"));
        assertTrue(screen("ROOM_FLOOR_OTHER_0").text().contains("Phòng A01"));
        when(service.getRoomById(4L)).thenReturn(legacy);
        assertTrue(screen("ROOM_DETAIL_4_F_OTHER_0").callbacks().contains("ROOM_FLOOR_OTHER_0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_FLOORS_bad", "ROOM_FLOOR_1_bad", "ROOM_FLOOR_1", "ROOM_FLOOR_1_0_extra"})
    void malformedFloorCallbacksHaveUsefulError(String action) {
        assertTrue(screen(action).text().contains("không hợp lệ"));
    }

    @Test
    void outdatedOrNegativePagesClampToAnExistingPage() {
        assertTrue(screen("ROOM_FLOORS_999").text().contains("Tầng 1"));
        assertTrue(screen("ROOM_FLOOR_1_-2").text().contains("Phòng 101"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_LIST_ALL", "ROOM_FLOORS_0", "ROOM_FLOOR_1_0", "ROOM_DETAIL_1_F_1_0"})
    void memberAndAdminCannotUseLandlordScreens(String action) {
        for (Role role : List.of(Role.MEMBER, Role.ADMIN)) {
            clearInvocations(sender, service);
            handler.handle(update(action), new User(999L, "other", "Other", role), sender);
            verify(sender).sendNotice(eq(123L), contains("không có quyền"));
            verifyNoInteractions(service);
        }
    }

    @Test
    void addRoomAndConfirmationStillPreserveDescription() {
        handler.handle(update("ROOM_ADD"), landlord, sender);
        assertEquals(UserConversationState.Step.CREATE_ROOM_NUMBER, states.getState(123L).getStep());
        when(service.createRoom(123L, "102", new BigDecimal("5000000"), 5, "phòng đẹp", new BigDecimal("25"), new BigDecimal("1500000"))).thenReturn(second);
        for (String text : List.of("102", "5000000", "5", "25", "1500000", "phòng đẹp")) {
            Update update = new Update();
            Message message = new Message();
            message.setChat(new Chat(123L, "private"));
            message.setText(text);
            update.setMessage(message);
            handler.handle(update, landlord, sender);
        }
        handler.handle(update("CONFIRM_CREATE_ROOM"), landlord, sender);
        verify(service).createRoom(123L, "102", new BigDecimal("5000000"), 5, "phòng đẹp", new BigDecimal("25"), new BigDecimal("1500000"));
        assertEquals(UserConversationState.Step.NONE, states.getState(123L).getStep());
    }

    @Test
    void existingDeleteCallbackStillUsesSameBusinessService() {
        handler.handle(update("ROOM_DELETE_1"), landlord, sender);
        verify(service).deleteRoom(123L, 1L);
        verify(sender).sendResultAndMenu(eq(123L), contains("Đã xóa phòng thành công"), any());
    }

    @Test
    void overviewAndFloorCountsUseOccupancyRatherThanStoredStatus() {
        when(service.getCurrentOccupantCount(1L)).thenReturn(0);
        when(service.getCurrentOccupantCount(2L)).thenReturn(2);
        when(service.getCurrentOccupantCount(3L)).thenReturn(5);
        Screen menu = screen("LANDLORD_ROOMS");
        assertTrue(menu.text().contains("Phòng còn chỗ: 2"));
        assertTrue(menu.text().contains("Phòng đã đầy: 1"));
        Screen floors = screen("ROOM_LIST_ALL");
        assertTrue(floors.text().contains("Tầng 1: 2 phòng · 🟢 2 còn chỗ"));
        assertTrue(floors.text().contains("Tầng 2: 1 phòng · 🟢 0 còn chỗ"));
        // Reopening a menu reads current data, including old keyboards.
        when(service.getCurrentOccupantCount(3L)).thenReturn(1);
        assertTrue(screen("LANDLORD_ROOMS").text().contains("Phòng còn chỗ: 3"));
    }

    @Test
    void filtersIncludeEmptyAndPartialRoomsButNeverAnotherFloor() {
        when(service.getCurrentOccupantCount(1L)).thenReturn(0);
        when(service.getCurrentOccupantCount(2L)).thenReturn(5);
        Screen available = screen("ROOM_FLOOR_1_0_AVAILABLE");
        assertTrue(available.text().contains("Phòng 101 · ⚪ Trống"));
        assertFalse(available.text().contains("Phòng 102"));
        assertFalse(available.text().contains("Phòng 201"));
        assertTrue(available.callbacks().contains("ROOM_DETAIL_1_F_1_0_AVAILABLE"));
        Screen full = screen("ROOM_FLOOR_1_0_FULL");
        assertTrue(full.text().contains("Phòng 102 · 🔴 Đã đầy"));
        assertFalse(full.text().contains("Phòng 101"));
        when(service.getCurrentOccupantCount(2L)).thenReturn(3);
        Screen partial = screen("ROOM_FLOOR_1_0_AVAILABLE");
        assertTrue(partial.text().contains("Phòng 102 · 🟢 Còn chỗ"));
        assertTrue(screen("ROOM_FLOOR_1_0_FULL").text().contains("Không có phòng phù hợp"));
    }

    @Test
    void emptyFilteredListKeepsFiltersAndBackAndInvalidDataRemainsAccessible() {
        Screen empty = screen("ROOM_FLOOR_1_0_FULL");
        assertTrue(empty.text().contains("Không có phòng phù hợp"));
        assertEquals(List.of("ROOM_FLOOR_1_0_ALL", "ROOM_FLOOR_1_0_AVAILABLE", "ROOM_FLOOR_1_0_FULL", "ROOM_FLOORS_0"), empty.callbacks());
        first.setMaxOccupants(null);
        assertTrue(screen("ROOM_FLOOR_1_0_ALL").text().contains("Sức chứa không hợp lệ"));
        assertFalse(screen("ROOM_FLOOR_1_0_AVAILABLE").text().contains("Phòng 101"));
    }

    @Test
    void filterAndPageSurviveDetailReturnAndFilterSwitchResetsPage() {
        List<Room> rooms = IntStream.rangeClosed(1, 21).mapToObj(n -> room(n, Integer.toString(100 + n))).toList();
        when(service.getAllRooms()).thenReturn(rooms);
        when(service.getRoomById(21L)).thenReturn(rooms.get(20));
        Screen filtered = screen("ROOM_FLOOR_1_2_AVAILABLE");
        assertTrue(filtered.callbacks().contains("ROOM_FLOOR_1_1_AVAILABLE"));
        assertTrue(filtered.callbacks().contains("ROOM_DETAIL_21_F_1_2_AVAILABLE"));
        assertTrue(screen("ROOM_DETAIL_21_F_1_2_AVAILABLE").callbacks().contains("ROOM_FLOOR_1_2_AVAILABLE"));
        assertTrue(screen("ROOM_FLOOR_1_0_ALL").text().contains("Phòng 101"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_FLOOR_1_0_BAD", "ROOM_DETAIL_1_F_1_0_BAD"})
    void invalidFilterDoesNotSilentlyShowAllRooms(String action) {
        if (action.startsWith("ROOM_DETAIL_")) {
            handler.handle(update(action), landlord, sender);
            verify(sender).sendNotice(eq(123L), contains("không hợp lệ"));
        } else assertTrue(screen(action).text().contains("không hợp lệ"));
    }

    private Screen screen(String action) {
        clearInvocations(sender);
        handler.handle(update(action), landlord, sender);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
        if (action.startsWith("ROOM_DETAIL_")) {
            verify(sender).sendOrEditMessageWithKeyboard(eq(123L), eq(77), text.capture(), keyboard.capture());
        } else {
            verify(sender).sendMessageWithKeyboard(eq(123L), text.capture(), keyboard.capture());
        }

        verify(sender, never()).sendMessage(anyLong(), anyString());
        return new Screen(text.getValue(), keyboard.getValue());
    }

    private Update update(String data) {
        Message message = new Message();
        message.setChat(new Chat(123L, "private"));
        message.setMessageId(77);
        CallbackQuery callback = new CallbackQuery();
        callback.setMessage(message);
        callback.setData(data);
        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    private static Room room(long id, String number) {
        Room room = new Room(number, new BigDecimal("5000000"), 5, "phòng đẹp");
        room.setId(id);
        return room;
    }

    private record Screen(String text, InlineKeyboardMarkup keyboard) {
        List<InlineKeyboardButton> buttons() {
            return keyboard.getKeyboard().stream().flatMap(List::stream).toList();
        }
        List<String> callbacks() {
            return buttons().stream().map(InlineKeyboardButton::getCallbackData).toList();
        }
    }
}
