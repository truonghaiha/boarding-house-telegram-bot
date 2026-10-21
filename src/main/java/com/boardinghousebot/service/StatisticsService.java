package com.boardinghousebot.service;

import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatisticsService {
    public record SystemStats(long users, long admins, long landlords, long members, long auditEntries) {}
    private final UserRepository users;
    private final AuditLogRepository audit;
    private final AuthorizationService authorization;

    public StatisticsService(UserRepository users, AuditLogRepository audit, AuthorizationService authorization) {
        this.users = users; this.audit = audit; this.authorization = authorization;
    }
    @Transactional(readOnly = true)
    public SystemStats systemStats(User actor) {
        authorization.requireAdmin(actor);
        var accounts = users.findAll();
        return new SystemStats(accounts.size(), accounts.stream().filter(u -> u.getRole() == Role.ADMIN).count(),
                accounts.stream().filter(u -> u.getRole() == Role.LANDLORD).count(),
                accounts.stream().filter(u -> u.getRole() == Role.MEMBER).count(), audit.count());
    }
}
