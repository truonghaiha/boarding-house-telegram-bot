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
        assertTrue(ex.getMessage().contains("đã được xếp vào phòng 101"));
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
