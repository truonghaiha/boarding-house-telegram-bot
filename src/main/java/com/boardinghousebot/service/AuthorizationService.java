package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

    private final TelegramConfig telegramConfig;

    public AuthorizationService(TelegramConfig telegramConfig) {
        this.telegramConfig = telegramConfig;
    }

    public boolean hasRole(User user, Role role) {
        if (user == null || user.getRole() == null || role == null) {
            return false;
        }
        if (user.getRole() != role) return false;
        if (role == Role.ADMIN) {
            try {
                return telegramConfig.isConfiguredAdmin(user.getTelegramId());
            } catch (IllegalStateException invalidConfiguration) {
                return false;
            }
        }
        return true;
    }

    public void requireRole(User user, Role requiredRole) {
        if (!hasRole(user, requiredRole)) {
            throw new BusinessException("Bạn không có quyền thực hiện chức năng này.");
        }
    }

    public void requireAdmin(User user) {
        requireRole(user, Role.ADMIN);
    }

    public void requireLandlord(User user) {
        requireRole(user, Role.LANDLORD);
    }

    public void requireMember(User user) {
        requireRole(user, Role.MEMBER);
    }
}
