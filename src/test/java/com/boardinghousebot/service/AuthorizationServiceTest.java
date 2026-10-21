package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthorizationServiceTest {

    private AuthorizationService authorizationService;
    private TelegramConfig telegramConfig;

    private User adminUser;
    private User landlordUser;
    private User memberUser;

    @BeforeEach
    void setUp() {
        telegramConfig = new TelegramConfig();
        telegramConfig.setAdminId("1");
        authorizationService = new AuthorizationService(telegramConfig);

        adminUser = new User(1L, "admin", "Admin", Role.ADMIN);
        landlordUser = new User(2L, "landlord", "Landlord", Role.LANDLORD);
        memberUser = new User(3L, "member", "Member", Role.MEMBER);
    }

    @Test
    @DisplayName("hasRole returns true for matching role")
    void hasRole_MatchingRole_ShouldReturnTrue() {
        assertTrue(authorizationService.hasRole(adminUser, Role.ADMIN));
        assertTrue(authorizationService.hasRole(landlordUser, Role.LANDLORD));
        assertTrue(authorizationService.hasRole(memberUser, Role.MEMBER));

        assertFalse(authorizationService.hasRole(memberUser, Role.ADMIN));
        assertFalse(authorizationService.hasRole(landlordUser, Role.ADMIN));
    }

    @Test
    @DisplayName("requireAdmin allows ADMIN and denies LANDLORD/MEMBER")
    void requireAdmin_Validation() {
        assertDoesNotThrow(() -> authorizationService.requireAdmin(adminUser));

        assertThrows(BusinessException.class, () -> authorizationService.requireAdmin(landlordUser));
        assertThrows(BusinessException.class, () -> authorizationService.requireAdmin(memberUser));
    }

    @Test
    void oldAdminIsDeniedEvenBeforeDatabaseSynchronization() {
        telegramConfig.setAdminId("2");
        assertFalse(authorizationService.hasRole(adminUser, Role.ADMIN));
        assertThrows(BusinessException.class, () -> authorizationService.requireAdmin(adminUser));
        assertThrows(BusinessException.class, () -> authorizationService.requireAdmin(landlordUser));
    }

    @Test
    void invalidConfigurationNeverAuthorizesPersistedAdmin() {
        telegramConfig.setAdminId("");
        assertFalse(authorizationService.hasRole(adminUser, Role.ADMIN));
        assertThrows(BusinessException.class, () -> authorizationService.requireAdmin(adminUser));
    }

    @Test
    @DisplayName("requireLandlord allows only LANDLORD")
    void requireLandlord_Validation() {
        assertDoesNotThrow(() -> authorizationService.requireLandlord(landlordUser));
        assertThrows(BusinessException.class, () -> authorizationService.requireLandlord(adminUser));

        assertThrows(BusinessException.class, () -> authorizationService.requireLandlord(memberUser));
    }

    @Test
    @DisplayName("requireMember allows only MEMBER")
    void requireMember_Validation() {
        assertDoesNotThrow(() -> authorizationService.requireMember(memberUser));
        assertThrows(BusinessException.class, () -> authorizationService.requireMember(landlordUser));
        assertThrows(BusinessException.class, () -> authorizationService.requireMember(adminUser));
    }
}
