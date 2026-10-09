package com.boardinghousebot.repository;

import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.room.RoomStatus;
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
class RoomRepositoryTest {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        roomRepository.deleteAll();
        userRepository.flush();
        roomRepository.flush();
    }

    @Test
    @DisplayName("Save room and findByRoomNumber works correctly")
    void testSaveAndFindByRoomNumber() {
        Room room = new Room("101", new BigDecimal("3500000"), 4, "Phòng 101");
        Room saved = roomRepository.save(room);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());

        Optional<Room> foundOpt = roomRepository.findByRoomNumber("101");
        assertTrue(foundOpt.isPresent());
        assertEquals(0, foundOpt.get().getMonthlyRent().compareTo(new BigDecimal("3500000")));
    }

    @Test
    @DisplayName("existsByRoomNumber returns true if room exists")
    void testExistsByRoomNumber() {
        Room room = new Room("102", new BigDecimal("4000000"), 2, "Phòng 102");
        roomRepository.save(room);

        assertTrue(roomRepository.existsByRoomNumber("102"));
        assertFalse(roomRepository.existsByRoomNumber("999"));
    }

    @Test
    @DisplayName("findByStatus filters rooms correctly")
    void testFindByStatus() {
        Room r1 = new Room("201", new BigDecimal("3000000"), 2, "Phòng 201");
        r1.setStatus(RoomStatus.AVAILABLE);
        roomRepository.save(r1);

        Room r2 = new Room("202", new BigDecimal("5000000"), 4, "Phòng 202");
        r2.setStatus(RoomStatus.OCCUPIED);
        roomRepository.save(r2);

        List<Room> available = roomRepository.findByStatus(RoomStatus.AVAILABLE);
        assertEquals(1, available.size());
        assertEquals("201", available.get(0).getRoomNumber());

        List<Room> occupied = roomRepository.findByStatus(RoomStatus.OCCUPIED);
        assertEquals(1, occupied.size());
        assertEquals("202", occupied.get(0).getRoomNumber());
    }
}
