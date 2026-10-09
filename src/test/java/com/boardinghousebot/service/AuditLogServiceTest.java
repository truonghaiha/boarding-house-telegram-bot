package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.audit.AuditLog;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.AuditLogRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class AuditLogServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private TelegramConfig telegramConfig;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        userRepository.deleteAll();
        telegramConfig.setAdminId("999999");
    }

    @Test
    @DisplayName("Verify exact AuditLog action format (USER_REGISTER, ADMIN_PROMOTE_LANDLORD, ADMIN_REVOKE_LANDLORD)")
    void testAuditLogActionFormats() {
        Long memberTelegramId = 555111L;
        Long adminTelegramId = 999999L;

        // 1. User Register -> USER_REGISTER
        User member = userService.getOrCreateUser(memberTelegramId, "member555", "Member 555");
        User admin = userService.getOrCreateUser(adminTelegramId, "admin999", "Admin 999");

        List<AuditLog> memberLogs = auditLogService.getLogsByTelegramId(memberTelegramId);
        assertFalse(memberLogs.isEmpty());
        assertEquals(AuditAction.USER_REGISTER, memberLogs.get(0).getAction());
        assertEquals("USER_REGISTER", memberLogs.get(0).getAction());

        // 2. Promote -> ADMIN_PROMOTE_LANDLORD
        userService.promoteToLandlord(admin.getTelegramId(), member.getTelegramId());
        List<AuditLog> adminLogs = auditLogService.getLogsByTelegramId(adminTelegramId);
        assertFalse(adminLogs.isEmpty());
        assertEquals(AuditAction.ADMIN_PROMOTE_LANDLORD, adminLogs.get(0).getAction());
        assertEquals("ADMIN_PROMOTE_LANDLORD", adminLogs.get(0).getAction());

        // 3. Revoke -> ADMIN_REVOKE_LANDLORD
        userService.revokeLandlord(admin.getTelegramId(), member.getTelegramId());
        adminLogs = auditLogService.getLogsByTelegramId(adminTelegramId);
        assertFalse(adminLogs.isEmpty());
        assertEquals(AuditAction.ADMIN_REVOKE_LANDLORD, adminLogs.get(0).getAction());
        assertEquals("ADMIN_REVOKE_LANDLORD", adminLogs.get(0).getAction());
    }
}
