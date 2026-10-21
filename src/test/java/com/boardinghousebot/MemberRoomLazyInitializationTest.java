package com.boardinghousebot;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.*;
import com.boardinghousebot.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MemberRoomLazyInitializationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private RoomService roomService;

    @Autowired
    private ContractService contractService;

    @Autowired
    private MeterReadingService meterReadingService;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private MeterReadingRepository meterReadingRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RoomStayRepository roomStayRepository;

    private static final Long LANDLORD_ID = 888111L;
    private static final Long MEMBER_ID = 8167463325L;

    @BeforeEach
    void setUp() {
        roomStayRepository.deleteAll();
        auditLogRepository.deleteAll();
        invoiceRepository.deleteAll();
        meterReadingRepository.deleteAll();
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();
    }

    @Test
    @DisplayName("A. UserRepository/UserService can load MEMBER + Room together")
    void testLoadMemberWithRoomTogether() {
        User landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Landlord User");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        Room room = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3500000"), 4, "Room 101");
        userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room.getId());

        Optional<User> loadedOpt = userService.findByTelegramIdWithRoom(MEMBER_ID);
        assertTrue(loadedOpt.isPresent());
        User loadedUser = loadedOpt.get();
        assertNotNull(loadedUser.getRoom());
        assertEquals("101", loadedUser.getRoom().getRoomNumber());
    }

    @Test
    @DisplayName("B. A MEMBER assigned to Room 101 can retrieve room information without LazyInitializationException")
    void testMemberRetrieveRoomWithoutLazyInitializationException() {
        User landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Landlord User");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        Room room = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3500000"), 4, "Room 101");
        userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room.getId());

        // Fetch user via getOrCreateUser (simulating Bot consume flow)
        User fetchedUser = userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");

        // Detached room access test
        assertDoesNotThrow(() -> {
            assertNotNull(fetchedUser.getRoom());
            assertEquals("101", fetchedUser.getRoom().getRoomNumber());
            assertEquals(new BigDecimal("3500000"), fetchedUser.getRoom().getMonthlyRent());
        });

        // Test roomService.getMemberRoom(...)
        Optional<Room> memberRoomOpt = roomService.getMemberRoom(MEMBER_ID);
        assertTrue(memberRoomOpt.isPresent());
        assertDoesNotThrow(() -> {
            assertEquals("101", memberRoomOpt.get().getRoomNumber());
        });
    }

    @Test
    @DisplayName("C. MEMBER meter reading flow works after room assignment")
    void testMemberMeterReadingFlow() {
        User landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Landlord User");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        Room room = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3500000"), 4, "Room 101");
        userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room.getId());

        meterReadingService.createReading(LANDLORD_ID, room.getId(), UtilityType.ELECTRICITY, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("100"), new BigDecimal("150"), new BigDecimal("3500"));
        meterReadingService.createReading(LANDLORD_ID, room.getId(), UtilityType.WATER, LocalDate.now().getMonthValue(), LocalDate.now().getYear(), new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000"));

        List<MeterReading> readings = meterReadingService.getMemberRoomReadings(MEMBER_ID);
        assertEquals(2, readings.size());

        User memberUser = userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");
        assertDoesNotThrow(() -> {
            String roomNum = memberUser.getRoom().getRoomNumber();
            assertEquals("101", roomNum);
        });
    }

    @Test
    @DisplayName("D. MEMBER invoice flow works after room assignment")
    void testMemberInvoiceFlow() {
        User landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Landlord User");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        Room room = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3500000"), 4, "Room 101");
        userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room.getId());

        contractService.createContract(LANDLORD_ID, MEMBER_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1), new BigDecimal("3500000"), new BigDecimal("3500000"));
        meterReadingService.createReading(LANDLORD_ID, room.getId(), UtilityType.ELECTRICITY, 9, 2026, new BigDecimal("100"), new BigDecimal("150"), new BigDecimal("3500"));
        meterReadingService.createReading(LANDLORD_ID, room.getId(), UtilityType.WATER, 9, 2026, new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("15000"));

        Invoice invoice = invoiceService.createInvoice(LANDLORD_ID, room.getId(), 9, 2026);
        assertNotNull(invoice);

        List<Invoice> memberInvoices = invoiceService.getMemberInvoices(MEMBER_ID);
        assertEquals(1, memberInvoices.size());

        User memberUser = userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");
        assertDoesNotThrow(() -> {
            String roomNum = memberUser.getRoom().getRoomNumber();
            assertEquals("101", roomNum);
        });
    }

    @Test
    @DisplayName("E. MEMBER contract flow works after room assignment")
    void testMemberContractFlow() {
        User landlord = userService.getOrCreateUser(LANDLORD_ID, "landlord", "Landlord User");
        landlord.setRole(Role.LANDLORD);
        userRepository.save(landlord);

        Room room = roomService.createRoom(LANDLORD_ID, "101", new BigDecimal("3500000"), 4, "Room 101");
        userService.getOrCreateUser(MEMBER_ID, "member_real", "Member Real");
        roomService.assignMemberToRoom(LANDLORD_ID, MEMBER_ID, room.getId());

        Contract contract = contractService.createContract(LANDLORD_ID, MEMBER_ID, LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1), new BigDecimal("3500000"), new BigDecimal("3500000"));
        assertNotNull(contract);

        Optional<Contract> contractOpt = contractService.getMemberActiveContract(MEMBER_ID);
        assertTrue(contractOpt.isPresent());
        assertDoesNotThrow(() -> {
            assertEquals("101", contractOpt.get().getRoom().getRoomNumber());
        });
    }
}
