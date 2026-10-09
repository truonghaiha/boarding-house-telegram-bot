package com.boardinghousebot.repository;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
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
class ContractRepositoryTest {

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoomRepository roomRepository;

    private User memberUser;
    private Room room101;

    @BeforeEach
    void setUp() {
        contractRepository.deleteAll();
        userRepository.deleteAll();
        roomRepository.deleteAll();
        contractRepository.flush();
        userRepository.flush();
        roomRepository.flush();

        room101 = new Room("101", new BigDecimal("3500000"), 2, "Mô tả 101");
        roomRepository.save(room101);

        memberUser = new User(1000000001L, "testmember1", "Test Member 1", Role.MEMBER);
        memberUser.setRoom(room101);
        userRepository.save(memberUser);
    }

    @Test
    @DisplayName("Save and find active contract by member telegram ID")
    void testSaveAndFindActiveContractByMember() {
        Contract contract = new Contract(memberUser, room101, LocalDate.now(), LocalDate.now().plusYears(1), new BigDecimal("3500000"), new BigDecimal("3500000"));
        Contract saved = contractRepository.save(contract);
        assertNotNull(saved.getId());

        Optional<Contract> activeOpt = contractRepository.findActiveContractByMemberTelegramId(memberUser.getTelegramId());
        assertTrue(activeOpt.isPresent());
        assertEquals("101", activeOpt.get().getRoom().getRoomNumber());
    }

    @Test
    @DisplayName("Find expiring contracts within date range")
    void testFindExpiringContracts() {
        LocalDate today = LocalDate.now();
        Contract contract = new Contract(memberUser, room101, today.minusMonths(11), today.plusDays(15), new BigDecimal("3500000"), new BigDecimal("3500000"));
        contractRepository.save(contract);

        List<Contract> expiring = contractRepository.findExpiringContracts(today, today.plusDays(30));
        assertEquals(1, expiring.size());
    }
}
