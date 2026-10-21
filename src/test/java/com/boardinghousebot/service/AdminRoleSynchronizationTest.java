package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.*;
import com.boardinghousebot.state.ConversationStateService;
import com.boardinghousebot.state.UserConversationState;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AdminRoleSynchronizationTest {
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired RoomRepository rooms;
    @Autowired ContractRepository contracts;
    @Autowired AuditLogRepository logs;
    @Autowired AuthorizationService authorization;
    @Autowired TelegramConfig config;
    @Autowired ConversationStateService states;
    String originalId;
    static final long OLD_ID = 88000001L;
    static final long NEW_ID = 88000002L;
    static final long OWNER_ID = 88000003L;

    @BeforeEach void setup() {
        originalId = config.getAdminId();
        config.setAdminId(Long.toString(OLD_ID));
    }

    @AfterEach void cleanup() {
        config.setAdminId(originalId);
        states.clearState(OLD_ID);
        states.clearState(NEW_ID);
    }

    User oldAdmin() {
        return userService.getOrCreateUser(OLD_ID, "old_admin", "Old Admin");
    }

    User newMember() {
        return userService.getOrCreateUser(NEW_ID, "new_admin", "New Admin");
    }

    @Test void transfersExistingAdminAndClearsConversationsWithoutChangingLandlords() {
        User old = oldAdmin();
        User next = newMember();
        User landlord = users.save(new User(OWNER_ID, "owner", "Owner", Role.LANDLORD));
        states.setState(OLD_ID, new UserConversationState(UserConversationState.Step.ADMIN_SEARCH_USERS));
        states.setState(NEW_ID, new UserConversationState(UserConversationState.Step.CREATE_ROOM_NUMBER));
        config.setAdminId(Long.toString(NEW_ID));
        userService.synchronizeConfiguredAdmin();
        assertEquals(Role.MEMBER, users.findById(old.getId()).orElseThrow().getRole());
        assertEquals(Role.ADMIN, users.findById(next.getId()).orElseThrow().getRole());
        assertEquals(Role.LANDLORD, users.findById(landlord.getId()).orElseThrow().getRole());
        assertEquals(UserConversationState.Step.NONE, states.getState(OLD_ID).getStep());
        assertEquals(UserConversationState.Step.NONE, states.getState(NEW_ID).getStep());
        assertTrue(logs.findByTelegramIdOrderByCreatedAtDesc(OLD_ID).stream()
                .anyMatch(log -> log.getAction().equals(AuditAction.CONFIG_REVOKE_ADMIN)));
        assertTrue(logs.findByTelegramIdOrderByCreatedAtDesc(NEW_ID).stream()
                .anyMatch(log -> log.getAction().equals(AuditAction.CONFIG_GRANT_ADMIN)));
        long count = logs.count();
        userService.synchronizeConfiguredAdmin();
        assertEquals(count, logs.count());
    }

    @Test void newUnregisteredIdGetsAdminOnlyOnFirstInteraction() {
        User old = oldAdmin();
        config.setAdminId(Long.toString(NEW_ID));
        userService.synchronizeConfiguredAdmin();
        assertEquals(Role.MEMBER, users.findById(old.getId()).orElseThrow().getRole());
        assertTrue(users.findByTelegramId(NEW_ID).isEmpty());
        User next = userService.getOrCreateUser(NEW_ID, "new_admin", "New Admin");
        assertEquals(Role.ADMIN, next.getRole());
        assertDoesNotThrow(() -> authorization.requireAdmin(next));
    }

    @Test void stalePersistedAdminIsDeniedBeforeSyncAndDemotedOnInteraction() {
        User old = oldAdmin();
        User target = newMember();
        config.setAdminId(Long.toString(NEW_ID));
        assertThrows(BusinessException.class, () -> userService.promoteToLandlord(OLD_ID, NEW_ID));
        assertThrows(BusinessException.class, () -> userService.revokeLandlord(OLD_ID, NEW_ID));
        assertThrows(BusinessException.class, () -> userService.searchUsersAsAdmin(old, "new_admin"));
        User reloaded = userService.getOrCreateUser(OLD_ID, "old_admin", "Old Admin");
        assertEquals(Role.MEMBER, reloaded.getRole());
        assertEquals(Role.MEMBER, target.getRole());
        assertEquals(Role.ADMIN, userService.getOrCreateUser(NEW_ID, "new_admin", "New Admin").getRole());
    }

    @Test void blankConfigurationFailsWithoutChangingRoles() {
        User old = oldAdmin();
        config.setAdminId(" ");
        assertThrows(IllegalStateException.class, userService::synchronizeConfiguredAdmin);
        assertEquals(Role.ADMIN, old.getRole());
        assertFalse(authorization.hasRole(old, Role.ADMIN));
    }

    @Test void roomLinkBlocksTransferBeforeAnyRoleChanges() {
        User old = oldAdmin();
        User next = newMember();
        Room room = rooms.save(new Room("ADMIN-SYNC-ROOM", BigDecimal.TEN, 2, ""));
        next.setRoom(room);
        users.saveAndFlush(next);
        config.setAdminId(Long.toString(NEW_ID));
        assertThrows(BusinessException.class, userService::synchronizeConfiguredAdmin);
        assertEquals(Role.ADMIN, old.getRole());
        assertEquals(Role.MEMBER, next.getRole());
    }

    @Test void activeContractBlocksTransferEvenWithoutCurrentRoomLink() {
        User old = oldAdmin();
        User next = newMember();
        Room room = rooms.save(new Room("ADMIN-SYNC-CONTRACT", BigDecimal.TEN, 2, ""));
        contracts.saveAndFlush(new Contract(next, room, LocalDate.now(), LocalDate.now().plusMonths(1),
                BigDecimal.TEN, BigDecimal.ZERO));
        config.setAdminId(Long.toString(NEW_ID));
        assertThrows(BusinessException.class, userService::synchronizeConfiguredAdmin);
        assertEquals(Role.ADMIN, old.getRole());
        assertEquals(Role.MEMBER, next.getRole());
    }

    @Test void demotesAllHistoricalAdminsAndCanPromoteConfiguredLandlord() {
        oldAdmin();
        users.save(new User(88000004L, "extra_admin", "Extra Admin", Role.ADMIN));
        User next = users.save(new User(NEW_ID, "next", "Next", Role.LANDLORD));
        config.setAdminId(Long.toString(NEW_ID));
        userService.synchronizeConfiguredAdmin();
        assertEquals(1, users.findByRole(Role.ADMIN).size());
        assertEquals(next.getId(), users.findByRole(Role.ADMIN).get(0).getId());
        assertEquals(Role.MEMBER, users.findByTelegramId(88000004L).orElseThrow().getRole());
    }
}
