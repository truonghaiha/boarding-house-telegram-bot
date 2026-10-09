package com.boardinghousebot.repository;

import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("save User and findByTelegramId works correctly")
    void testSaveAndFindByTelegramId() {
        User user = new User(888888L, "test_repo_user", "Test Repo User", Role.MEMBER);
        User saved = userRepository.save(user);

        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());

        Optional<User> foundOpt = userRepository.findByTelegramId(888888L);
        assertTrue(foundOpt.isPresent());
        assertEquals("test_repo_user", foundOpt.get().getUsername());
    }

    @Test
    @DisplayName("existsByTelegramId checks user existence")
    void testExistsByTelegramId() {
        User user = new User(777777L, "exists_user", "Exists User", Role.MEMBER);
        userRepository.save(user);

        assertTrue(userRepository.existsByTelegramId(777777L));
        assertFalse(userRepository.existsByTelegramId(999000L));
    }

    @Test
    @DisplayName("findByRole filters users by role")
    void testFindByRole() {
        userRepository.save(new User(101L, "m1", "Member 1", Role.MEMBER));
        userRepository.save(new User(102L, "l1", "Landlord 1", Role.LANDLORD));
        userRepository.save(new User(103L, "l2", "Landlord 2", Role.LANDLORD));

        List<User> landlords = userRepository.findByRole(Role.LANDLORD);
        assertEquals(2, landlords.size());

        List<User> members = userRepository.findByRole(Role.MEMBER);
        assertEquals(1, members.size());
    }
}
