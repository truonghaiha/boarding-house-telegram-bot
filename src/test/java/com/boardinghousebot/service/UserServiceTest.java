package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.UserRepository;
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
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.boardinghousebot.repository.AuditLogRepository auditLogRepository;

    @Autowired
    private TelegramConfig telegramConfig;

    private static final Long ADMIN_TELEGRAM_ID = 999999L;
    private static final Long USER_TELEGRAM_ID = 111111L;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        telegramConfig.setAdminId(ADMIN_TELEGRAM_ID.toString());
    }

    @Test
    @DisplayName("getOrCreateUser creates new user with MEMBER role by default")
    void getOrCreateUser_NewUser_ShouldCreateMember() {
        User user = userService.getOrCreateUser(USER_TELEGRAM_ID, "user_a", "User A");

        assertNotNull(user);
        assertNotNull(user.getId());
        assertEquals(USER_TELEGRAM_ID, user.getTelegramId());
        assertEquals("user_a", user.getUsername());
        assertEquals("User A", user.getFullName());
        assertEquals(Role.MEMBER, user.getRole());
    }

    @Test
    @DisplayName("getOrCreateUser creates user with ADMIN role if telegramId matches admin-id")
    void getOrCreateUser_AdminId_ShouldCreateAdmin() {
        User admin = userService.getOrCreateUser(ADMIN_TELEGRAM_ID, "admin_user", "Admin System");

        assertNotNull(admin);
        assertEquals(ADMIN_TELEGRAM_ID, admin.getTelegramId());
        assertEquals(Role.ADMIN, admin.getRole());
    }

    @Test
    @DisplayName("getOrCreateUser on existing user does not create duplicate")
    void getOrCreateUser_ExistingUser_ShouldNotDuplicate() {
        User firstCall = userService.getOrCreateUser(USER_TELEGRAM_ID, "user_a", "User A");
        User secondCall = userService.getOrCreateUser(USER_TELEGRAM_ID, "user_a_updated", "User A Updated");

        assertEquals(firstCall.getId(), secondCall.getId());
        assertEquals("user_a_updated", secondCall.getUsername());
        assertEquals(1, userRepository.count());
    }

    @Test
    @DisplayName("findByTelegramId returns correct user")
    void findByTelegramId_ShouldReturnUser() {
        userService.getOrCreateUser(USER_TELEGRAM_ID, "user_a", "User A");

        Optional<User> foundOpt = userService.findByTelegramId(USER_TELEGRAM_ID);
        assertTrue(foundOpt.isPresent());
        assertEquals("user_a", foundOpt.get().getUsername());
    }

    @Test
    @DisplayName("promote MEMBER to LANDLORD succeeds")
    void promoteToLandlord_MemberUser_ShouldPromoteToLandlord() {
        User admin = userService.getOrCreateUser(ADMIN_TELEGRAM_ID, "admin", "Admin");
        User user = userService.getOrCreateUser(USER_TELEGRAM_ID, "user_a", "User A");

        User promoted = userService.promoteToLandlord(admin.getTelegramId(), user.getTelegramId());

        assertEquals(Role.LANDLORD, promoted.getRole());
    }

    @Test
    @DisplayName("revoke LANDLORD to MEMBER succeeds")
    void revokeLandlord_LandlordUser_ShouldRevokeToMember() {
        User admin = userService.getOrCreateUser(ADMIN_TELEGRAM_ID, "admin", "Admin");
        User user = userService.getOrCreateUser(USER_TELEGRAM_ID, "user_a", "User A");

        userService.promoteToLandlord(admin.getTelegramId(), user.getTelegramId());
        User revoked = userService.revokeLandlord(admin.getTelegramId(), user.getTelegramId());

        assertEquals(Role.MEMBER, revoked.getRole());
    }

    @Test
    @DisplayName("Attempting to promote or revoke ADMIN is DENIED and throws BusinessException")
    void adminImmutability_ShouldDenyRoleChange() {
        User admin = userService.getOrCreateUser(ADMIN_TELEGRAM_ID, "admin", "Admin");

        BusinessException promoteException = assertThrows(BusinessException.class, () ->
                userService.promoteToLandlord(admin.getTelegramId(), admin.getTelegramId()));
        assertEquals("Không thể thay đổi quyền của ADMIN.", promoteException.getMessage());

        BusinessException revokeException = assertThrows(BusinessException.class, () ->
                userService.revokeLandlord(admin.getTelegramId(), admin.getTelegramId()));
        assertEquals("Không thể thay đổi quyền của ADMIN.", revokeException.getMessage());
    }

    @Test
    @DisplayName("Promote non-existent target throws BusinessException")
    void promote_NonExistentUser_ShouldThrowException() {
        User admin = userService.getOrCreateUser(ADMIN_TELEGRAM_ID, "admin", "Admin");

        BusinessException ex = assertThrows(BusinessException.class, () ->
                userService.promoteToLandlord(admin.getTelegramId(), 99999999L));
        assertEquals("Không tìm thấy người dùng.", ex.getMessage());
    }

    @Test
    @DisplayName("FULL CRITICAL SCENARIO: User A register -> MEMBER, Admin promote -> LANDLORD, Admin revoke -> MEMBER, Admin self role change -> DENIED")
    void fullCriticalScenarioTest() {
        // Step 1: User A (/start) -> MEMBER
        User userA = userService.getOrCreateUser(111L, "usera", "User A");
        assertEquals(Role.MEMBER, userA.getRole());

        // Step 2: Admin (/start) -> ADMIN
        User admin = userService.getOrCreateUser(ADMIN_TELEGRAM_ID, "admin", "System Admin");
        assertEquals(Role.ADMIN, admin.getRole());

        // Step 3: Admin promote 111 -> LANDLORD
        User promotedA = userService.promoteToLandlord(admin.getTelegramId(), 111L);
        assertEquals(Role.LANDLORD, promotedA.getRole());

        // Step 4: Admin revoke 111 -> MEMBER
        User revokedA = userService.revokeLandlord(admin.getTelegramId(), 111L);
        assertEquals(Role.MEMBER, revokedA.getRole());

        // Step 5: Admin tries to modify self role -> DENIED
        assertThrows(BusinessException.class, () ->
                userService.promoteToLandlord(admin.getTelegramId(), admin.getTelegramId()));
        assertThrows(BusinessException.class, () ->
                userService.revokeLandlord(admin.getTelegramId(), admin.getTelegramId()));
    }

    @Test
    @DisplayName("getUsersByRoleWithRoom returns users with role and eagerly fetches room")
    void getUsersByRoleWithRoom_ShouldFetchUsersWithRoom() {
        User member = userService.getOrCreateUser(USER_TELEGRAM_ID, "user_m", "Member M");
        List<User> membersWithRoom = userService.getUsersByRoleWithRoom(Role.MEMBER);
        assertFalse(membersWithRoom.isEmpty());
        assertEquals(Role.MEMBER, membersWithRoom.get(0).getRole());
    }
}
