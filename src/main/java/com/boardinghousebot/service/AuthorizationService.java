package com.boardinghousebot.service;

import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class AuthorizationService {

    public boolean hasRole(User user, Role role) {
        if (user == null || user.getRole() == null || role == null) {
            return false;
        }
        return user.getRole() == role;
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
        if (user == null || user.getRole() == null) {
            throw new BusinessException("Bạn không có quyền thực hiện chức năng này.");
        }
        if (user.getRole() != Role.LANDLORD && user.getRole() != Role.ADMIN) {
            throw new BusinessException("Bạn không có quyền thực hiện chức năng này.");
        }
    }

    public void requireMember(User user) {
        if (user == null || user.getRole() == null) {
            throw new BusinessException("Bạn không có quyền thực hiện chức năng này.");
        }
    }
}
