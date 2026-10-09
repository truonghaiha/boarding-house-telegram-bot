package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditLog;
import com.boardinghousebot.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public AuditLog logAction(Long telegramId, String action, String description) {
        AuditLog auditLog = new AuditLog(telegramId, action, description);
        AuditLog savedLog = auditLogRepository.save(auditLog);
        log.info("AUDIT LOG: [User: {}] - Action: {} - Description: {}", telegramId, action, description);
        return savedLog;
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getAllLogs() {
        return auditLogRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<AuditLog> getLogsByTelegramId(Long telegramId) {
        return auditLogRepository.findByTelegramIdOrderByCreatedAtDesc(telegramId);
    }
}
