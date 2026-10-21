package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.AuditLogRepository;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.RoomRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class ContractServiceTest {

    @Autowired
    private ContractService contractService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TelegramConfig telegramConfig;

    @Autowired
    private com.boardinghousebot.repository.RoomStayRepository roomStayRepository;

    private User landlord;
    private User admin;
    private User member1;
    private User member2;
    private User memberNoRoom;
    private Room room101;
    private Room room102;

    private static final Long LANDLORD_ID = 888111L;
    private static final Long ADMIN_ID = 999999L;
    private static final Long MEMBER1_ID = 1000000001L;
    private static final Long MEMBER2_ID = 1000000002L;
    private static final Long MEMBER_NO_ROOM_ID = 1000000003L;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();
        auditLogRepository.flush();
        contractRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        telegramConfig.setAdminId(ADMIN_ID.toString());

        admin = userService.getOrCreateUser(ADMIN_ID, "admin", "Admin System");
        landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Chủ Nhà A");
        userService.promoteToLandlord(admin.getTelegramId(), landlord.getTelegramId());

        member1 = userService.getOrCreateUser(MEMBER1_ID, "member1", "Test Member 1");
        member2 = userService.getOrCreateUser(MEMBER2_ID, "member2", "Test Member 2");
        memberNoRoom = userService.getOrCreateUser(MEMBER_NO_ROOM_ID, "member3", "Test Member 3");

        room101 = roomService.createRoom(landlord.getTelegramId(), "101", new BigDecimal("3500000"), 2, "Mô tả 101");
        room102 = roomService.createRoom(landlord.getTelegramId(), "102", new BigDecimal("4000000"), 2, "Mô tả 102");

        roomService.assignMemberToRoom(landlord.getTelegramId(), member1.getTelegramId(), room101.getId());
        roomService.assignMemberToRoom(landlord.getTelegramId(), member2.getTelegramId(), room102.getId());
    }

    @Test
    @DisplayName("1. Create ACTIVE contract successfully")
    void test1_CreateContract_Success() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);

        Contract contract = contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));

        assertNotNull(contract.getId());
        assertEquals(ContractStatus.ACTIVE, contract.getStatus());
        assertEquals("101", contract.getRoom().getRoomNumber());
        assertEquals(member1.getTelegramId(), contract.getMember().getTelegramId());
    }

    @Test
    void rejectsResidentWhoMovedFromSelectedRoomBeforeConfirmation() {
        roomService.moveMemberToRoom(LANDLORD_ID, MEMBER1_ID, room102.getId());
        BusinessException ex = assertThrows(BusinessException.class, () ->
                contractService.createContract(LANDLORD_ID, MEMBER1_ID, LocalDate.now(),
                        LocalDate.now().plusYears(1), new BigDecimal("3500000"), BigDecimal.ZERO, room101.getId()));
        assertEquals("Người thuê không còn ở phòng đã chọn. Vui lòng tạo lại hợp đồng.", ex.getMessage());
        assertEquals(0, contractRepository.count());
    }

    @Test
    void selectedRoomContractCanBeCreatedAndRoomBecomesIneligible() {
        assertFalse(contractService.hasActiveContractForRoom(room101.getId()));
        Contract contract = contractService.createContract(LANDLORD_ID, MEMBER1_ID, LocalDate.now(),
                LocalDate.now().plusYears(1), new BigDecimal("3500000"), BigDecimal.ZERO, room101.getId());
        assertEquals(room101.getId(), contract.getRoom().getId());
        assertTrue(contractService.hasActiveContractForRoom(room101.getId()));
        assertFalse(contractService.hasActiveContractForRoom(room102.getId()));
    }

    @Test
    @DisplayName("2 & 3. Reject creating contract for ADMIN or LANDLORD")
    void test2_3_CreateContractForAdminOrLandlord_ShouldThrowException() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);

        assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), admin.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000")));

        assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), landlord.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000")));
    }

    @Test
    @DisplayName("4. Reject creating contract for MEMBER without a Room")
    void test4_CreateContractForMemberWithoutRoom_ShouldThrowException() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), memberNoRoom.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000")));
        assertEquals("Người thuê chưa được xếp vào phòng nào.", ex.getMessage());
    }

    @Test
    @DisplayName("5. Reject creating a second ACTIVE contract for the same MEMBER")
    void test5_CreateSecondActiveContractForMember_ShouldThrowException() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);

        contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000")));
        assertEquals("Người thuê này đã có hợp đồng đang hiệu lực.", ex.getMessage());
    }

    @Test
    @DisplayName("6. Reject creating a second ACTIVE contract for the same ROOM")
    void test6_CreateSecondActiveContractForRoom_ShouldThrowException() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);

        contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));

        User memberInSameRoom = userService.getOrCreateUser(1000000004L, "member4", "Member 4");
        roomService.assignMemberToRoom(landlord.getTelegramId(), memberInSameRoom.getTelegramId(), room101.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), memberInSameRoom.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000")));
        assertTrue(ex.getMessage().contains("đã có hợp đồng đang hiệu lực"));
    }

    @Test
    @DisplayName("7. Reject endDate <= startDate")
    void test7_InvalidDates_ShouldThrowException() {
        LocalDate start = LocalDate.now();
        LocalDate invalidEnd = start.minusDays(1);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, invalidEnd, new BigDecimal("3500000"), new BigDecimal("3500000")));
        assertEquals("Ngày kết thúc phải sau ngày bắt đầu.", ex.getMessage());
    }

    @Test
    @DisplayName("8 & 9. Reject monthlyRent <= 0 or deposit < 0")
    void test8_9_InvalidRentOrDeposit_ShouldThrowException() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);

        assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("0"), new BigDecimal("3500000")));

        assertThrows(BusinessException.class, () ->
                contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("-100")));
    }

    @Test
    @DisplayName("10. Update ACTIVE contract successfully")
    void test10_UpdateActiveContract_Success() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);
        Contract contract = contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));

        LocalDate newEnd = end.plusMonths(6);
        BigDecimal newRent = new BigDecimal("4000000");
        BigDecimal newDeposit = new BigDecimal("4000000");

        Contract updated = contractService.updateContract(landlord.getTelegramId(), contract.getId(), start, newEnd, newRent, newDeposit);

        assertEquals(newEnd, updated.getEndDate());
        assertEquals(0, newRent.compareTo(updated.getMonthlyRent()));
        assertEquals(0, newDeposit.compareTo(updated.getDeposit()));
    }

    @Test
    @DisplayName("11. Reject updating ENDED contract")
    void test11_UpdateEndedContract_ShouldThrowException() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);
        Contract contract = contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));
        contractService.endContract(landlord.getTelegramId(), contract.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                contractService.updateContract(landlord.getTelegramId(), contract.getId(), start, end.plusMonths(1), new BigDecimal("4000000"), new BigDecimal("4000000")));
        assertEquals("Không thể chỉnh sửa hợp đồng đã kết thúc.", ex.getMessage());
    }

    @Test
    @DisplayName("12 & 13. End contract successfully and verify it persists with ENDED status")
    void test12_13_EndContract_Success() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);
        Contract contract = contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));
        roomService.assignMemberToRoom(landlord.getTelegramId(), memberNoRoom.getTelegramId(), room101.getId());

        Contract ended = contractService.endContract(landlord.getTelegramId(), contract.getId());
        assertEquals(ContractStatus.ENDED, ended.getStatus());

        Contract fromDb = contractService.getContractById(contract.getId());
        assertEquals(ContractStatus.ENDED, fromDb.getStatus());
        assertEquals(room101.getId(), fromDb.getRoom().getId());
        assertEquals(member1.getTelegramId(), fromDb.getMember().getTelegramId());
        for (var resident : List.of(member1, memberNoRoom)) {
            assertNull(userRepository.findByTelegramId(resident.getTelegramId()).orElseThrow().getRoom());
            assertTrue(roomStayRepository.findByMemberTelegramIdAndLeftAtIsNull(resident.getTelegramId()).isEmpty());
            assertFalse(roomStayRepository.findByMemberTelegramIdAndRoomId(resident.getTelegramId(), room101.getId()).isEmpty());
        }
        assertEquals(0, roomService.getCurrentOccupantCount(room101.getId()));
        assertEquals(com.boardinghousebot.domain.room.RoomStatus.AVAILABLE, roomService.getRoomById(room101.getId()).getStatus());
        assertEquals(room102.getId(), member2.getRoom().getId());

        roomService.assignMemberToRoom(landlord.getTelegramId(), memberNoRoom.getTelegramId(), room101.getId());
        assertThrows(BusinessException.class, () -> contractService.endContract(landlord.getTelegramId(), contract.getId()));
        assertEquals(room101.getId(), memberNoRoom.getRoom().getId());
    }

    @Test
    @DisplayName("14. MEMBER can view own contract")
    void test14_MemberCanViewOwnContract() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);
        contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));

        Optional<Contract> ownContract = contractService.getMemberActiveContract(member1.getTelegramId());
        assertTrue(ownContract.isPresent());
        assertEquals("101", ownContract.get().getRoom().getRoomNumber());
    }

    @Test
    @DisplayName("15. MEMBER authorization: cannot create/update/end contract")
    void test15_MemberAuthorizationDenied() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);
        Contract contract = contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));

        assertThrows(BusinessException.class, () ->
                contractService.createContract(member1.getTelegramId(), member2.getTelegramId(), start, end, new BigDecimal("3000000"), new BigDecimal("3000000")));

        assertThrows(BusinessException.class, () ->
                contractService.updateContract(member1.getTelegramId(), contract.getId(), start, end.plusMonths(1), new BigDecimal("3000000"), new BigDecimal("3000000")));

        assertThrows(BusinessException.class, () ->
                contractService.endContract(member1.getTelegramId(), contract.getId()));
    }

    @Test
    @DisplayName("16. LANDLORD can view list of contracts")
    void test16_LandlordCanListContracts() {
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusYears(1);
        contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, end, new BigDecimal("3500000"), new BigDecimal("3500000"));

        List<Contract> all = contractService.getAllContracts();
        assertEquals(1, all.size());

        List<Contract> active = contractService.getActiveContracts();
        assertEquals(1, active.size());
    }

    @Test
    @DisplayName("17. Get expiring contracts returns contracts within threshold")
    void test17_GetExpiringContracts() {
        LocalDate start = LocalDate.now().minusMonths(11);
        LocalDate endExpiring = LocalDate.now().plusDays(15);
        contractService.createContract(landlord.getTelegramId(), member1.getTelegramId(), start, endExpiring, new BigDecimal("3500000"), new BigDecimal("3500000"));

        List<Contract> expiring = contractService.getExpiringContracts(30);
        assertEquals(1, expiring.size());
    }
}
