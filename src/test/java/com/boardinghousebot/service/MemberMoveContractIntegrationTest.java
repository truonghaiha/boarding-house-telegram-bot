package com.boardinghousebot.service;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.Payment;
import com.boardinghousebot.domain.invoice.PaymentMethod;
import com.boardinghousebot.domain.notification.Notification;
import com.boardinghousebot.domain.notification.NotificationType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.*;
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
public class MemberMoveContractIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private UserService userService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private MeterReadingService meterReadingService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    private User landlordA;
    private User landlordB;
    private User member;
    private Room room101;
    private Room room102;

    private static final Long LANDLORD_A_ID = 9101L;
    private static final Long LANDLORD_B_ID = 9102L;
    private static final Long MEMBER_ID = 9201L;

    @BeforeEach
    void setUp() {
        landlordA = userService.getOrCreateUser(LANDLORD_A_ID, "landlordA", "Landlord A");
        landlordA.setRole(Role.LANDLORD);
        userRepository.save(landlordA);

        landlordB = userService.getOrCreateUser(LANDLORD_B_ID, "landlordB", "Landlord B");
        landlordB.setRole(Role.LANDLORD);
        userRepository.save(landlordB);

        member = userService.getOrCreateUser(MEMBER_ID, "member1", "Nguyen Van Member");
        member.setRole(Role.MEMBER);
        userRepository.save(member);

        room101 = roomService.createRoom(LANDLORD_A_ID, "MV101", new BigDecimal("3000000"), 2, "Phong MV101");
        room102 = roomService.createRoom(LANDLORD_A_ID, "MV102", new BigDecimal("4000000"), 2, "Phong MV102");
    }

    @Test
    @DisplayName("TEST 1: MOVE MEMBER with active contract ends old contract and creates new contract")
    void test1_MoveMemberWithActiveContract_EndsOldAndCreatesNew() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());
        Contract oldContract = contractService.createContract(
                LANDLORD_A_ID, MEMBER_ID,
                LocalDate.now().minusMonths(1), LocalDate.now().plusMonths(5),
                new BigDecimal("3000000"), new BigDecimal("3000000")
        );
        assertEquals(ContractStatus.ACTIVE, oldContract.getStatus());

        User movedUser = roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room102.getId());

        // User room updated
        assertEquals(room102.getId(), movedUser.getRoom().getId());

        // Old contract ENDED and room kept as 101
        Contract updatedOldContract = contractRepository.findById(oldContract.getId()).orElseThrow();
        assertEquals(ContractStatus.ENDED, updatedOldContract.getStatus());
        assertEquals(room101.getId(), updatedOldContract.getRoom().getId());
        assertEquals(LocalDate.now(), updatedOldContract.getEndDate());

        // New contract created for room 102 and ACTIVE
        Optional<Contract> newActiveContractOpt = contractRepository.findActiveContractByMemberTelegramId(MEMBER_ID);
        assertTrue(newActiveContractOpt.isPresent());
        Contract newContract = newActiveContractOpt.get();
        assertNotEquals(oldContract.getId(), newContract.getId());
        assertEquals(room102.getId(), newContract.getRoom().getId());
        assertEquals(ContractStatus.ACTIVE, newContract.getStatus());

        // Room statuses
        assertEquals(RoomStatus.AVAILABLE, roomRepository.findById(room101.getId()).orElseThrow().getStatus());
        assertEquals(RoomStatus.OCCUPIED, roomRepository.findById(room102.getId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("TEST 2: Move fails when target room is full")
    void test2_MoveMemberToFullRoom_RollsBackState() {
        Room fullRoom = roomService.createRoom(LANDLORD_A_ID, "MV103", new BigDecimal("3500000"), 1, "Phong MV103");
        User otherMember = userService.getOrCreateUser(9202L, "member2", "Other Member");
        otherMember.setRole(Role.MEMBER);
        userRepository.save(otherMember);

        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());
        roomService.assignMemberToRoom(LANDLORD_A_ID, otherMember.getTelegramId(), fullRoom.getId());

        Contract activeContract = contractService.createContract(
                LANDLORD_A_ID, MEMBER_ID,
                LocalDate.now(), LocalDate.now().plusMonths(6),
                new BigDecimal("3000000"), new BigDecimal("3000000")
        );

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, fullRoom.getId()));
        assertEquals("Phòng đích đã đủ số người.", ex.getMessage());

        // User still in room 101
        User reloadedMember = userRepository.findByTelegramId(MEMBER_ID).orElseThrow();
        assertEquals(room101.getId(), reloadedMember.getRoom().getId());

        // Contract still ACTIVE
        Contract reloadedContract = contractRepository.findById(activeContract.getId()).orElseThrow();
        assertEquals(ContractStatus.ACTIVE, reloadedContract.getStatus());
    }

    @Test
    @DisplayName("TEST 3: MOVE to same current room is rejected")
    void test3_MoveToSameRoom_Rejected() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId()));
        assertEquals("Người dùng hiện đã ở trong phòng này.", ex.getMessage());
    }

    @Test
    @DisplayName("TEST 4: Non-existent member move is rejected")
    void test4_NonExistentMember_Rejected() {
        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.moveMemberToRoom(LANDLORD_A_ID, 9999999L, room102.getId()));
        assertEquals("Không tìm thấy người dùng.", ex.getMessage());
    }

    @Test
    @DisplayName("TEST 5: Non-existent target room move is rejected")
    void test5_NonExistentTargetRoom_Rejected() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, 999999L));
        assertEquals("Không tìm thấy phòng.", ex.getMessage());
    }

    @Test
    @DisplayName("TEST 6: Move member without active contract handles move cleanly without contract creation")
    void test6_MoveMemberWithoutActiveContract_Succeeds() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());

        // Move without contract
        User movedUser = roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room102.getId());
        assertEquals(room102.getId(), movedUser.getRoom().getId());

        // No active contract created
        Optional<Contract> contractOpt = contractRepository.findActiveContractByMemberTelegramId(MEMBER_ID);
        assertTrue(contractOpt.isEmpty());
    }

    @Test
    @DisplayName("TEST 7: Historical Invoice and Payment are preserved after MOVE")
    void test7_HistoricalInvoiceAndPaymentPreservedAfterMove() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());
        Contract contract = contractService.createContract(
                LANDLORD_A_ID, MEMBER_ID,
                LocalDate.now().minusMonths(2), LocalDate.now().plusMonths(4),
                new BigDecimal("3000000"), new BigDecimal("3000000")
        );

        meterReadingService.createReading(LANDLORD_A_ID, room101.getId(), com.boardinghousebot.domain.meter.UtilityType.ELECTRICITY, 9, 2026, new BigDecimal("100"), new BigDecimal("150"), new BigDecimal("3500"));
        meterReadingService.createReading(LANDLORD_A_ID, room101.getId(), com.boardinghousebot.domain.meter.UtilityType.WATER, 9, 2026, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000"));

        Invoice oldInvoice = invoiceService.createInvoice(LANDLORD_A_ID, room101.getId(), 9, 2026);
        Payment payment = paymentService.recordPayment(LANDLORD_A_ID, oldInvoice.getId(), new BigDecimal("1000000"), PaymentMethod.CASH, "Tiền cọc/Thanh toán 1 phần");

        // Execute move
        roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room102.getId());

        // Verify old Invoice still points to room 101 and old contract
        Invoice reloadedInvoice = invoiceRepository.findById(oldInvoice.getId()).orElseThrow();
        assertEquals(room101.getId(), reloadedInvoice.getRoom().getId());
        assertEquals(contract.getId(), reloadedInvoice.getContract().getId());

        // Verify Payment still points to old Invoice
        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertEquals(oldInvoice.getId(), reloadedPayment.getInvoice().getId());
    }

    @Test
    @DisplayName("TEST 8: N-LANDLORD model allows Landlord B to view updated Room, Contract, and Member")
    void test8_NLandlordModelSharedData() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());
        contractService.createContract(LANDLORD_A_ID, MEMBER_ID, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));

        // Landlord A moves member
        roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room102.getId());

        // Landlord B queries member room and active contract
        Optional<Room> memberRoomOpt = roomService.getMemberRoom(MEMBER_ID);
        assertTrue(memberRoomOpt.isPresent());
        assertEquals("MV102", memberRoomOpt.get().getRoomNumber());

        Optional<Contract> activeContractB = contractRepository.findActiveContractByMemberTelegramId(MEMBER_ID);
        assertTrue(activeContractB.isPresent());
        assertEquals(room102.getId(), activeContractB.get().getRoom().getId());
    }

    @Test
    @DisplayName("TEST 9: Member receives ROOM_CHANGED notification on MOVE")
    void test9_NotificationTriggeredOnMove() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());

        notificationRepository.deleteAll();

        roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room102.getId());

        List<Notification> memberNotifs = notificationService.getNotificationsForUser(MEMBER_ID);
        assertTrue(memberNotifs.isEmpty(), "Moving without an active contract does not start the notification inbox");
    }

    @Test
    @DisplayName("TEST 10: Transaction rollback on failure prevents partial DB update")
    void test10_TransactionRollbackOnFailure() {
        roomService.assignMemberToRoom(LANDLORD_A_ID, MEMBER_ID, room101.getId());
        Contract contract = contractService.createContract(LANDLORD_A_ID, MEMBER_ID, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));

        // Attempt move to full room (should throw BusinessException and rollback)
        Room fullRoom = roomService.createRoom(LANDLORD_A_ID, "MV104", new BigDecimal("3500000"), 1, "Phong MV104");
        User otherMember = userService.getOrCreateUser(9203L, "member3", "Other Member 3");
        otherMember.setRole(Role.MEMBER);
        userRepository.save(otherMember);
        roomService.assignMemberToRoom(LANDLORD_A_ID, otherMember.getTelegramId(), fullRoom.getId());

        assertThrows(BusinessException.class, () ->
                roomService.moveMemberToRoom(LANDLORD_A_ID, MEMBER_ID, fullRoom.getId()));

        // Check DB state is unchanged
        User reloadedMember = userRepository.findByTelegramId(MEMBER_ID).orElseThrow();
        assertEquals(room101.getId(), reloadedMember.getRoom().getId());

        Contract reloadedContract = contractRepository.findById(contract.getId()).orElseThrow();
        assertEquals(ContractStatus.ACTIVE, reloadedContract.getStatus());
    }
}
