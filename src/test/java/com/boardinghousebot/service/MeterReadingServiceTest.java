package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditLog;
import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.AuditLogRepository;
import com.boardinghousebot.repository.MeterReadingRepository;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MeterReadingServiceTest {
    @Autowired
    private RoomStayService roomStayService;

    @Autowired
    private MeterReadingService meterReadingService;

    @Autowired
    private MeterReadingRepository meterReadingRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private com.boardinghousebot.repository.ContractRepository contractRepository;

    @Autowired private com.boardinghousebot.repository.InvoiceRepository invoices;
    @Autowired private com.boardinghousebot.repository.PaymentRepository payments;

    private User landlord;
    private User member103;
    private User member102;
    private Room room103;
    private Room room102;

    @BeforeEach
    void setUp() {
        payments.deleteAll();
        invoices.deleteAll();
        auditLogRepository.deleteAll();
        contractRepository.deleteAll();
        meterReadingRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();
        contractRepository.flush();
        meterReadingRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        landlord = new User(999000L, "landlord", "Test Landlord", Role.LANDLORD);
        landlord = userRepository.save(landlord);

        room103 = new Room("SR903", new BigDecimal("3500000"), 4, "Phòng SR903");
        room103.setStatus(RoomStatus.OCCUPIED);
        room103 = roomRepository.save(room103);

        room102 = new Room("SR902", new BigDecimal("3000000"), 2, "Phòng SR902");
        room102.setStatus(RoomStatus.OCCUPIED);
        room102 = roomRepository.save(room102);

        member103 = new User(100001L, "member103", "Member 103", Role.MEMBER);
        member103.setRoom(room103);
        member103 = userRepository.save(member103);

        member102 = new User(100002L, "member102", "Member 102", Role.MEMBER);
        member102.setRoom(room102);
        member102 = userRepository.save(member102);
        // This fixture represents occupancy starting before the fixed billing periods.
        roomStayService.enter(member103, room103, java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        roomStayService.enter(member102, room102, java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("Landlord creates electricity reading successfully")
    void testCreateElectricityReading_Success() {
        MeterReading reading = meterReadingService.createReading(
                landlord.getTelegramId(),
                room103.getId(),
                UtilityType.ELECTRICITY,
                9, 2026,
                new BigDecimal("1200"),
                new BigDecimal("1350"),
                new BigDecimal("3500")
        );

        assertNotNull(reading.getId());
        assertEquals("SR903", reading.getRoom().getRoomNumber());
        assertEquals(UtilityType.ELECTRICITY, reading.getUtilityType());
        assertEquals(new BigDecimal("150"), reading.getConsumption());
        assertEquals(new BigDecimal("525000"), reading.getTotalCharge());

        List<AuditLog> logs = auditLogRepository.findAll();
        assertFalse(logs.isEmpty());
        assertTrue(logs.stream().anyMatch(l -> l.getAction().contains("METER_READING")));
    }

    @Test
    @DisplayName("Landlord creates water reading successfully")
    void testCreateWaterReading_Success() {
        MeterReading reading = meterReadingService.createReading(
                landlord.getTelegramId(),
                room103.getId(),
                UtilityType.WATER,
                9, 2026,
                new BigDecimal("100"),
                new BigDecimal("112"),
                new BigDecimal("15000")
        );

        assertNotNull(reading.getId());
        assertEquals(UtilityType.WATER, reading.getUtilityType());
        assertEquals(new BigDecimal("12"), reading.getConsumption());
        assertEquals(new BigDecimal("180000"), reading.getTotalCharge());
    }

    @Test
    @DisplayName("Reject duplicate meter reading for same room, type, and period")
    void testRejectDuplicateReading() {
        meterReadingService.createReading(
                landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500")
        );

        assertThrows(BusinessException.class, () ->
                meterReadingService.createReading(
                        landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                        new BigDecimal("1350"), new BigDecimal("1500"), new BigDecimal("3500")
                )
        );
    }

    @Test
    @DisplayName("Reject current reading less than previous reading")
    void testRejectCurrentLessThanPrevious() {
        assertThrows(BusinessException.class, () ->
                meterReadingService.createReading(
                        landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                        new BigDecimal("1350"), new BigDecimal("1200"), new BigDecimal("3500")
                )
        );
    }

    @Test
    @DisplayName("Reject invalid inputs (negative reading, zero unitPrice, invalid month)")
    void testRejectInvalidInputs() {
        // Negative previous
        assertThrows(BusinessException.class, () ->
                meterReadingService.createReading(landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                        new BigDecimal("-10"), new BigDecimal("100"), new BigDecimal("3500"))
        );

        // Unit price zero
        assertThrows(BusinessException.class, () ->
                meterReadingService.createReading(landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                        new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("0"))
        );

        // Invalid month
        assertThrows(BusinessException.class, () ->
                meterReadingService.createReading(landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 13, 2026,
                        new BigDecimal("100"), new BigDecimal("200"), new BigDecimal("3500"))
        );
    }

    @Test
    @DisplayName("Member cannot create meter reading")
    void testMemberCannotCreateReading() {
        assertThrows(BusinessException.class, () ->
                meterReadingService.createReading(
                        member103.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                        new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500")
                )
        );
    }

    @Test
    @DisplayName("Landlord updates meter reading and recalculates consumption/charge")
    void testUpdateReading_Success() {
        MeterReading reading = meterReadingService.createReading(
                landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500")
        );

        MeterReading updated = meterReadingService.updateReading(
                landlord.getTelegramId(), reading.getId(),
                new BigDecimal("1200"), new BigDecimal("1400"), new BigDecimal("3500")
        );

        assertEquals(new BigDecimal("200"), updated.getConsumption());
        assertEquals(new BigDecimal("700000"), updated.getTotalCharge());
    }

    @Test
    @DisplayName("Landlord deletes meter reading successfully")
    void testDeleteReading_Success() {
        MeterReading reading = meterReadingService.createReading(
                landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500")
        );

        meterReadingService.deleteReading(landlord.getTelegramId(), reading.getId());

        assertThrows(BusinessException.class, () -> meterReadingService.getReadingById(reading.getId()));
    }

    @Test
    @DisplayName("Member can only view own room meter readings (isolation test)")
    void testMemberIsolation() {
        meterReadingService.createReading(landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500"));
        meterReadingService.createReading(landlord.getTelegramId(), room102.getId(), UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("500"), new BigDecimal("600"), new BigDecimal("3500"));

        List<MeterReading> member103Readings = meterReadingService.getMemberRoomReadings(member103.getTelegramId());
        assertEquals(1, member103Readings.size());
        assertEquals("SR903", member103Readings.get(0).getRoom().getRoomNumber());

        List<MeterReading> member102Readings = meterReadingService.getMemberRoomReadings(member102.getTelegramId());
        assertEquals(1, member102Readings.size());
        assertEquals("SR902", member102Readings.get(0).getRoom().getRoomNumber());
    }

    @Test
    @DisplayName("Retrieve filtered readings and available billing periods")
    void testGetFilteredReadingsAndLatestBillingPeriod() {
        meterReadingService.createReading(landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 8, 2026,
                new BigDecimal("1000"), new BigDecimal("1100"), new BigDecimal("3500"));
        meterReadingService.createReading(landlord.getTelegramId(), room103.getId(), UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1100"), new BigDecimal("1250"), new BigDecimal("3500"));

        List<int[]> availablePeriods = meterReadingService.getAvailableBillingPeriods();
        assertEquals(2, availablePeriods.size());

        int[] latest = meterReadingService.getLatestBillingPeriod();
        assertEquals(9, latest[0]);
        assertEquals(2026, latest[1]);

        List<MeterReading> latestReadings = meterReadingService.getLatestPeriodReadings();
        assertEquals(1, latestReadings.size());
        assertEquals(9, latestReadings.get(0).getBillingMonth());

        List<MeterReading> filtered = meterReadingService.getFilteredReadings(room103.getId(), UtilityType.ELECTRICITY, 8, 2026);
        assertEquals(1, filtered.size());
        assertEquals(8, filtered.get(0).getBillingMonth());
    }
}
