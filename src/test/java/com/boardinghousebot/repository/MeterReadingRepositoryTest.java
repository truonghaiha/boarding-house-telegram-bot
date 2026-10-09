package com.boardinghousebot.repository;

import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class MeterReadingRepositoryTest {

    @Autowired
    private MeterReadingRepository meterReadingRepository;

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Room room103;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_meter_reading_room_type_period ON meter_readings(room_id, utility_type, billing_month, billing_year)");
        contractRepository.deleteAll();
        meterReadingRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();
        contractRepository.flush();
        meterReadingRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        room103 = new Room("RP903", new BigDecimal("3500000"), 4, "Phòng RP903 test");
        room103 = roomRepository.save(room103);
    }

    @Test
    @DisplayName("Save and find MeterReading by id with room details")
    void testSaveAndFindByIdWithDetails() {
        MeterReading reading = new MeterReading(room103, UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500"));
        MeterReading saved = meterReadingRepository.save(reading);

        Optional<MeterReading> foundOpt = meterReadingRepository.findByIdWithDetails(saved.getId());
        assertTrue(foundOpt.isPresent());

        MeterReading found = foundOpt.get();
        assertEquals("RP903", found.getRoom().getRoomNumber());
        assertEquals(UtilityType.ELECTRICITY, found.getUtilityType());
        assertEquals(new BigDecimal("150.00"), found.getConsumption().setScale(2));
        assertEquals(new BigDecimal("525000.00"), found.getTotalCharge().setScale(2));
    }

    @Test
    @DisplayName("Enforce unique constraint on (room_id, utility_type, billing_month, billing_year)")
    void testUniqueConstraint() {
        MeterReading reading1 = new MeterReading(room103, UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500"));
        meterReadingRepository.saveAndFlush(reading1);

        MeterReading duplicate = new MeterReading(room103, UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1350"), new BigDecimal("1500"), new BigDecimal("3500"));

        assertThrows(org.springframework.dao.DataAccessException.class, () -> meterReadingRepository.saveAndFlush(duplicate));
    }

    @Test
    @DisplayName("Find meter readings by room and utility type")
    void testFindByRoomAndUtilityType() {
        MeterReading elec = new MeterReading(room103, UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500"));
        MeterReading water = new MeterReading(room103, UtilityType.WATER, 9, 2026,
                new BigDecimal("100"), new BigDecimal("112"), new BigDecimal("15000"));
        meterReadingRepository.save(elec);
        meterReadingRepository.save(water);

        List<MeterReading> elecList = meterReadingRepository.findByRoomIdAndUtilityTypeWithDetails(room103.getId(), UtilityType.ELECTRICITY);
        assertEquals(1, elecList.size());
        assertEquals(UtilityType.ELECTRICITY, elecList.get(0).getUtilityType());

        List<MeterReading> allRoomReadings = meterReadingRepository.findByRoomIdWithDetails(room103.getId());
        assertEquals(2, allRoomReadings.size());
    }

    @Test
    @DisplayName("Filter meter readings by room, utility type, month, and year combinations")
    void testFilterReadingsWithDetails() {
        MeterReading r1 = new MeterReading(room103, UtilityType.ELECTRICITY, 8, 2026,
                new BigDecimal("1000"), new BigDecimal("1200"), new BigDecimal("3500"));
        MeterReading r2 = new MeterReading(room103, UtilityType.WATER, 8, 2026,
                new BigDecimal("50"), new BigDecimal("60"), new BigDecimal("15000"));
        MeterReading r3 = new MeterReading(room103, UtilityType.ELECTRICITY, 9, 2026,
                new BigDecimal("1200"), new BigDecimal("1350"), new BigDecimal("3500"));

        meterReadingRepository.save(r1);
        meterReadingRepository.save(r2);
        meterReadingRepository.save(r3);

        // Filter by month and year
        List<MeterReading> month8 = meterReadingRepository.filterReadingsWithDetails(null, null, 8, 2026);
        assertEquals(2, month8.size());

        // Filter by utility type
        List<MeterReading> waterOnly = meterReadingRepository.filterReadingsWithDetails(null, UtilityType.WATER, null, null);
        assertEquals(1, waterOnly.size());

        // Combined filter
        List<MeterReading> exactMatch = meterReadingRepository.filterReadingsWithDetails(room103.getId(), UtilityType.ELECTRICITY, 9, 2026);
        assertEquals(1, exactMatch.size());
        assertEquals(new BigDecimal("1350.00"), exactMatch.get(0).getCurrentReading().setScale(2));

        // Test distinct billing periods
        List<Object[]> periods = meterReadingRepository.findDistinctBillingPeriods();
        assertTrue(periods.size() >= 2);
    }
}
