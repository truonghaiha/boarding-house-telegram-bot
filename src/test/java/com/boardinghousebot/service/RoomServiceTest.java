package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.AuditLogRepository;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class RoomServiceTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TelegramConfig telegramConfig;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private ContractService contractService;

    private User landlord;
    private User admin;
    private User member1;
    private User member2;

    private static final Long LANDLORD_ID = 888111L;
    private static final Long ADMIN_ID = 999999L;
    private static final Long MEMBER1_ID = 111111L;
    private static final Long MEMBER2_ID = 222222L;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.flush();
        roomRepository.flush();

        telegramConfig.setAdminId(ADMIN_ID.toString());

        admin = userService.getOrCreateUser(ADMIN_ID, "admin", "Admin System");
        landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Chủ Nhà A");
        userService.promoteToLandlord(admin.getTelegramId(), landlord.getTelegramId());

        member1 = userService.getOrCreateUser(MEMBER1_ID, "member1", "Nguyễn Văn A");
        member2 = userService.getOrCreateUser(MEMBER2_ID, "member2", "Trần Văn B");
    }

    @Test
    @DisplayName("1. Create room successfully")
    void test1_CreateRoom_Success() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Ban công rộng");

        assertNotNull(room.getId());
        assertEquals("101", room.getRoomNumber());
        assertEquals(RoomStatus.AVAILABLE, room.getStatus());
        assertEquals(0, roomService.getCurrentOccupantCount(room));
    }

    @Test
    @DisplayName("2. Reject duplicate room number")
    void test2_CreateRoom_DuplicateNumber_ShouldThrowException() {
        roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("4000000"), 2, "Mô tả khác"));
        assertEquals("Số phòng đã tồn tại.", ex.getMessage());
    }

    @Test
    @DisplayName("3. Reject invalid monthly rent (<= 0)")
    void test3_CreateRoom_InvalidRent_ShouldThrowException() {
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("0"), 4, "Mô tả"));
        assertEquals("Giá thuê phải lớn hơn 0.", ex1.getMessage());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                roomService.createRoom(landlord.getTelegramId(), "102", new BigDecimal("-100"), 4, "Mô tả"));
        assertEquals("Giá thuê phải lớn hơn 0.", ex2.getMessage());
    }

    @Test
    @DisplayName("4. Reject invalid max occupants (<= 0)")
    void test4_CreateRoom_InvalidMaxOccupants_ShouldThrowException() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 0, "Mô tả"));
        assertEquals("Số người tối đa phải lớn hơn 0.", ex.getMessage());
    }

    @Test
    @DisplayName("5. Update room details successfully")
    void test5_UpdateRoom_Success() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả cũ");

        Room updated = roomService.updateRoom(landlord.getTelegramId(), room.getId(), "101-A", new BigDecimal("4000000"), 3, "Mô tả mới");
        assertEquals("101-A", updated.getRoomNumber());
        assertEquals(new BigDecimal("4000000"), updated.getMonthlyRent());
        assertEquals(3, updated.getMaxOccupants());
    }

    @Test
    @DisplayName("6. Delete empty room successfully")
    void test6_DeleteEmptyRoom_Success() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");
        Long roomId = room.getId();

        roomService.deleteRoom(landlord.getTelegramId(), roomId);
        assertFalse(roomRepository.findById(roomId).isPresent());
    }

    @Test
    @DisplayName("7. Reject delete occupied room")
    void test7_DeleteOccupiedRoom_ShouldThrowException() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");
        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.deleteRoom(landlord.getTelegramId(), room.getId()));
        assertEquals("Không thể xóa phòng đang có người thuê.", ex.getMessage());
    }

    @Test
    @DisplayName("8. Assign MEMBER to room successfully and sync status")
    void test8_AssignMemberToRoom_Success() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");

        User assigned = roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room.getId());
        assertNotNull(assigned.getRoom());
        assertEquals(room.getId(), assigned.getRoom().getId());

        Room updatedRoom = roomService.getRoomById(room.getId());
        assertEquals(RoomStatus.OCCUPIED, updatedRoom.getStatus());
        assertEquals(1, roomService.getCurrentOccupantCount(updatedRoom));
    }

    @Test
    @DisplayName("9. Reject assigning nonexistent user")
    void test9_AssignNonexistentUser_ShouldThrowException() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.assignMemberToRoom(landlord.getTelegramId(), 999888777L, room.getId()));
        assertEquals("Không tìm thấy người dùng.", ex.getMessage());
    }

    @Test
    @DisplayName("10 & 11. Reject assigning ADMIN or LANDLORD to room")
    void test10_11_AssignAdminOrLandlord_ShouldThrowException() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");

        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                roomService.assignMemberToRoom(landlord.getTelegramId(), admin.getTelegramId(), room.getId()));
        assertEquals("Chỉ MEMBER mới có thể được xếp vào phòng.", ex1.getMessage());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                roomService.assignMemberToRoom(landlord.getTelegramId(), landlord.getTelegramId(), room.getId()));
        assertEquals("Chỉ MEMBER mới có thể được xếp vào phòng.", ex2.getMessage());
    }

    @Test
    @DisplayName("12. Reject assigning MEMBER who already has a room")
    void test12_AssignMemberAlreadyInRoom_ShouldThrowException() {
        Room room1 = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");
        Room room2 = roomService.createRoom(landlord.getTelegramId(), "102", new BigDecimal("4000000"), 4, "Mô tả");

        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room1.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room2.getId()));
        assertEquals("Người này đã thuê phòng, không thể thêm vào phòng mới.", ex.getMessage());
    }

    @Test
    @DisplayName("13. Reject assigning to full room")
    void test13_AssignToFullRoom_ShouldThrowException() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 1, "Phòng 1 người");
        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.assignMemberToRoom(landlord.getTelegramId(), member2.getTelegramId(), room.getId()));
        assertEquals("Phòng đã đủ số người.", ex.getMessage());
    }

    @Test
    @DisplayName("14. Remove member from room and sync status to AVAILABLE when empty")
    void test14_RemoveMemberFromRoom_Success() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");
        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room.getId());

        User removed = roomService.removeMemberFromRoom(landlord.getTelegramId(), member1.getTelegramId());
        assertNull(removed.getRoom());

        Room updatedRoom = roomService.getRoomById(room.getId());
        assertEquals(RoomStatus.AVAILABLE, updatedRoom.getStatus());
        assertEquals(0, roomService.getCurrentOccupantCount(updatedRoom));
    }

    @Test
    void removalFromRoomDetailRejectsResidentWhoMovedToAnotherRoom() {
        Room firstRoom = roomService.createRoom(LANDLORD_ID, "301", new BigDecimal("3500000"), 2, "");
        Room nextRoom = roomService.createRoom(LANDLORD_ID, "302", new BigDecimal("3500000"), 2, "");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, firstRoom.getId());
        roomService.moveMemberToRoom(LANDLORD_ID, MEMBER1_ID, nextRoom.getId());
        assertThrows(BusinessException.class, () ->
                roomService.removeMemberFromRoom(LANDLORD_ID, MEMBER1_ID, firstRoom.getId()));
        assertEquals(nextRoom.getId(), roomService.getMemberRoom(MEMBER1_ID).orElseThrow().getId());
        roomService.removeMemberFromRoom(LANDLORD_ID, MEMBER1_ID, nextRoom.getId());
        assertTrue(roomService.getMemberRoom(MEMBER1_ID).isEmpty());
        assertEquals(RoomStatus.AVAILABLE, roomService.getRoomById(nextRoom.getId()).getStatus());
    }

    @Test
    void roomManagementCountsAndFiltersFollowSqliteChanges() {
        var states = new com.boardinghousebot.state.ConversationStateService();
        var handler = new com.boardinghousebot.bot.handler.RoomCommandHandler(roomService, states);
        final String[] text = new String[1];
        var sender = new com.boardinghousebot.bot.command.BotMessageSender() {
            public void sendMessage(Long chatId, String message) {}
            public void sendMessageWithKeyboard(Long chatId, String message,
                    org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup keyboard) {
                text[0] = message;
            }
        };
        var actor = userRepository.findByTelegramId(LANDLORD_ID).orElseThrow();
        java.util.function.Consumer<String> open = action -> {
            var message = new org.telegram.telegrambots.meta.api.objects.message.Message();
            message.setChat(new org.telegram.telegrambots.meta.api.objects.chat.Chat(LANDLORD_ID, "private"));
            var query = new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
            query.setMessage(message);
            query.setData(action);
            var update = new org.telegram.telegrambots.meta.api.objects.Update();
            update.setCallbackQuery(query);
            handler.handle(update, actor, sender);
        };
        open.accept("LANDLORD_ROOMS");
        assertTrue(text[0].contains("Tổng số phòng: 0"));
        Room first = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("4000000"), 2, "");
        Room upstairs = roomService.createRoom(LANDLORD_ID, "201", new BigDecimal("4000000"), 2, "");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, first.getId());
        open.accept("ROOM_FLOOR_1_0_AVAILABLE");
        assertTrue(text[0].contains("Phòng 101 · 🟢 Còn chỗ"));
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER2_ID, first.getId());
        open.accept("LANDLORD_ROOMS");
        assertTrue(text[0].contains("Tổng số phòng: 2"));
        assertTrue(text[0].contains("Tổng số tầng: 2"));
        assertTrue(text[0].contains("Phòng còn chỗ: 1"));
        assertTrue(text[0].contains("Phòng đã đầy: 1"));
        open.accept("ROOM_FLOOR_1_0_AVAILABLE");
        assertTrue(text[0].contains("Không có phòng phù hợp"));
        open.accept("ROOM_FLOOR_1_0_FULL");
        assertTrue(text[0].contains("Phòng 101 · 🔴 Đã đầy"));
        roomService.removeMemberFromRoom(LANDLORD_ID, MEMBER2_ID);
        open.accept("ROOM_LIST_ALL");
        assertTrue(text[0].contains("Tầng 1: 1 phòng · 🟢 1 còn chỗ"));
        roomService.moveMemberToRoom(LANDLORD_ID, MEMBER1_ID, upstairs.getId());
        open.accept("ROOM_FLOOR_1_0_ALL");
        assertTrue(text[0].contains("Phòng 101 · ⚪ Trống"));
        roomService.deleteRoom(LANDLORD_ID, first.getId());
        open.accept("LANDLORD_ROOMS");
        assertTrue(text[0].contains("Tổng số phòng: 1"));
        assertTrue(text[0].contains("Tổng số tầng: 1"));
        open.accept("ROOM_LIST_ALL");
        assertFalse(text[0].contains("Tầng 1:"));
        assertTrue(text[0].contains("Tầng 2:"));
    }

    @Test
    void tenantPhonePersistsAndTelegramProfileRefreshKeepsIt() {
        Room room = roomService.createRoom(LANDLORD_ID, "401", new BigDecimal("4000000"), 2, "");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, room.getId(), "0912 345 678");
        entityManager.flush(); entityManager.clear();
        assertEquals("0912345678", userRepository.findByTelegramId(MEMBER1_ID).orElseThrow().getPhoneNumber());
        userService.updateTenantPhoneNumber(LANDLORD_ID, MEMBER1_ID, "+84 912 345 679");
        entityManager.flush(); entityManager.clear();
        User refreshed = userService.getOrCreateUser(MEMBER1_ID, "changed", "New Telegram name");
        assertEquals("+84912345679", refreshed.getPhoneNumber());
        roomService.removeMemberFromRoom(LANDLORD_ID, MEMBER1_ID);
        assertEquals("+84912345679", userRepository.findByTelegramId(MEMBER1_ID).orElseThrow().getPhoneNumber());
    }

    @Test
    void editingRoomFieldsPreservesOccupancyAndContractRent() {
        Room room = roomService.createRoom(LANDLORD_ID, "501", new BigDecimal("4000000"), 2, "Cũ");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, room.getId());
        var lease = contractService.createContract(LANDLORD_ID, MEMBER1_ID, java.time.LocalDate.now(),
                java.time.LocalDate.now().plusYears(1), new BigDecimal("4000000"), BigDecimal.ZERO);
        roomService.updateRoomRent(LANDLORD_ID, room.getId(), new BigDecimal("5000000"));
        roomService.updateRoomDescription(LANDLORD_ID, room.getId(), "Mới");
        entityManager.flush(); entityManager.clear();
        Room updated = roomService.getRoomById(room.getId());
        assertEquals(0, new BigDecimal("5000000").compareTo(updated.getMonthlyRent()));
        assertEquals("Mới", updated.getDescription());
        assertEquals(2, updated.getMaxOccupants());
        assertEquals(1, roomService.getCurrentOccupantCount(updated));
        assertEquals(0, new BigDecimal("4000000").compareTo(contractService.getContractById(lease.getId()).getMonthlyRent()));
        assertThrows(BusinessException.class, () -> roomService.updateRoomRent(MEMBER1_ID, updated.getId(), BigDecimal.ONE));
        assertThrows(BusinessException.class, () -> roomService.updateRoomDescription(ADMIN_ID, updated.getId(), "Denied"));
    }

    @Test
    void tenantContactRejectsMissingPhoneAndUnauthorizedChanges() {
        Room room = roomService.createRoom(LANDLORD_ID, "402", new BigDecimal("4000000"), 2, "");
        for (String phone : new String[] {null, "", "abc", "123", "/skip"}) {
            assertThrows(BusinessException.class, () -> roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, room.getId(), phone));
        }
        assertTrue(roomService.getMemberRoom(MEMBER1_ID).isEmpty());
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER1_ID, room.getId(), "0912345678");
        assertThrows(BusinessException.class, () -> userService.updateTenantPhoneNumber(MEMBER2_ID, MEMBER1_ID, "0987654321"));
        assertThrows(BusinessException.class, () -> userService.updateTenantPhoneNumber(ADMIN_ID, MEMBER1_ID, "0987654321"));
        assertThrows(BusinessException.class, () -> userService.updateTenantPhoneNumber(LANDLORD_ID, MEMBER1_ID, ""));
        assertEquals("0912345678", userRepository.findByTelegramId(MEMBER1_ID).orElseThrow().getPhoneNumber());
    }

    @Test
    @DisplayName("15. Move member to another room successfully and sync occupancy")
    void test15_MoveMemberToAnotherRoom_Success() {
        Room room1 = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Phòng 101");
        Room room2 = roomService.createRoom(landlord.getTelegramId(), "102", new BigDecimal("4000000"), 4, "Phòng 102");

        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room1.getId());
        assertEquals(RoomStatus.OCCUPIED, roomService.getRoomById(room1.getId()).getStatus());

        User movedUser = roomService.moveMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room2.getId());
        assertEquals(room2.getId(), movedUser.getRoom().getId());

        assertEquals(RoomStatus.AVAILABLE, roomService.getRoomById(room1.getId()).getStatus());
        assertEquals(RoomStatus.OCCUPIED, roomService.getRoomById(room2.getId()).getStatus());
    }

    @Test
    @DisplayName("16. Reject move to full room")
    void test16_MoveMemberToFullRoom_ShouldThrowException() {
        Room room1 = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Phòng 101");
        Room room2 = roomService.createRoom(landlord.getTelegramId(), "102", new BigDecimal("4000000"), 1, "Phòng 102 (1 người)");

        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room1.getId());
        roomService.assignMemberToRoom(landlord.getTelegramId(), member2.getTelegramId(), room2.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.moveMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room2.getId()));
        assertEquals("Phòng đích đã đủ số người.", ex.getMessage());
    }

    @Test
    @DisplayName("17. Member can view own room")
    void test17_MemberCanViewOwnRoom() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Phòng 101");
        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room.getId());

        Optional<Room> memberRoomOpt = roomService.getMemberRoom(member1.getTelegramId());
        assertTrue(memberRoomOpt.isPresent());
        assertEquals("101", memberRoomOpt.get().getRoomNumber());
    }

    @Test
    @DisplayName("18. Authorization checks: MEMBER cannot create/delete room or assign members")
    void test18_AuthorizationChecks_MemberDenied() {
        Room room = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 4, "Mô tả");

        assertThrows(BusinessException.class, () ->
                roomService.createRoom(member1.getTelegramId(), "102", new BigDecimal("3000000"), 2, ""));

        assertThrows(BusinessException.class, () ->
                roomService.deleteRoom(member1.getTelegramId(), room.getId()));

        assertThrows(BusinessException.class, () ->
                roomService.assignMemberToRoom(member1.getTelegramId(), member2.getTelegramId(), room.getId()));
    }

    @Test
    @DisplayName("19. Room pure domain business rules check")
    void test19_RoomPureDomainRules() {
        Room room = new Room("101", new BigDecimal("3500000"), 2, "Mô tả");
        assertFalse(room.isFull(0));
        assertFalse(room.isFull(1));
        assertTrue(room.isFull(2));
        assertTrue(room.isFull(3));

        assertTrue(room.hasAvailableCapacity(0));
        assertTrue(room.hasAvailableCapacity(1));
        assertFalse(room.hasAvailableCapacity(2));
    }
}
