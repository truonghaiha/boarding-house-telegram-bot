package com.boardinghousebot.service;

import com.boardinghousebot.config.TelegramConfig;
import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final TelegramConfig telegramConfig;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public UserService(UserRepository userRepository,
                       TelegramConfig telegramConfig,
                       AuthorizationService authorizationService,
                       AuditLogService auditLogService,
                       org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.telegramConfig = telegramConfig;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public User getOrCreateUser(Long telegramId, String username, String fullName) {
        if (telegramId == null || telegramId <= 0) {
            throw new BusinessException("Telegram ID không hợp lệ.");
        }

        Optional<User> existingUserOpt = userRepository.findByTelegramIdWithRoom(telegramId);
        boolean isAdminConfigured = isTelegramAdmin(telegramId);

        if (existingUserOpt.isPresent()) {
            User user = existingUserOpt.get();
            boolean updated = false;

            if (isAdminConfigured && user.getRole() != Role.ADMIN) {
                user.setRole(Role.ADMIN);
                updated = true;
            }
            if (username != null && !username.equals(user.getUsername())) {
                user.setUsername(username);
                updated = true;
            }
            if (fullName != null && !fullName.isBlank() && !fullName.equals(user.getFullName())) {
                user.setFullName(fullName);
                updated = true;
            }

            if (updated) {
                user = userRepository.save(user);
            }
            return user;
        }

        Role initialRole = isAdminConfigured ? Role.ADMIN : Role.MEMBER;
        String nameToUse = (fullName != null && !fullName.isBlank()) ? fullName : (username != null ? username : "Telegram User");
        User newUser = new User(telegramId, username, nameToUse, initialRole);
        User savedUser = userRepository.save(newUser);

        auditLogService.logAction(telegramId, AuditAction.USER_REGISTER, "Tạo người dùng mới với vai trò " + initialRole);
        log.info("Registered new user: ID={}, TelegramID={}, Role={}", savedUser.getId(), telegramId, initialRole);

        return savedUser;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByTelegramId(Long telegramId) {
        return userRepository.findByTelegramId(telegramId);
    }

    @Transactional(readOnly = true)
    public Optional<User> findByTelegramIdWithRoom(Long telegramId) {
        return userRepository.findByTelegramIdWithRoom(telegramId);
    }

    @Transactional(readOnly = true)
    public User getUserById(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
    }

    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<User> getUsersByRole(Role role) {
        return userRepository.findByRole(role);
    }

    @Transactional(readOnly = true)
    public List<User> getUsersByRoleWithRoom(Role role) {
        return userRepository.findByRoleWithRoom(role);
    }

    @Transactional(readOnly = true)
    public List<User> searchUsers(String query) {
        if (query == null || query.isBlank()) {
            return getAllUsers();
        }
        List<User> byUsername = userRepository.findByUsernameContainingIgnoreCase(query);
        List<User> byFullName = userRepository.findByFullNameContainingIgnoreCase(query);
        
        return java.util.stream.Stream.concat(byUsername.stream(), byFullName.stream())
                .distinct()
                .toList();
    }

    @Transactional
    public User promoteToLandlord(Long adminTelegramId, Long targetTelegramId) {
        User adminUser = userRepository.findByTelegramId(adminTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin Admin."));

        authorizationService.requireAdmin(adminUser);

        User targetUser = userRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));

        if (targetUser.getRole() == Role.ADMIN) {
            throw new BusinessException("Không thể thay đổi quyền của ADMIN.");
        }

        if (targetUser.getRole() == Role.LANDLORD) {
            log.info("User {} is already LANDLORD", targetTelegramId);
            return targetUser;
        }

        Role oldRole = targetUser.getRole();
        targetUser.setRole(Role.LANDLORD);
        User updatedUser = userRepository.save(targetUser);
        auditLogService.logAction(adminTelegramId, AuditAction.ADMIN_PROMOTE_LANDLORD, "Cấp quyền LANDLORD cho user " + targetTelegramId);

        eventPublisher.publishEvent(new com.boardinghousebot.observer.RoleChangedEvent(updatedUser, oldRole, Role.LANDLORD));

        return updatedUser;
    }

    @Transactional
    public User revokeLandlord(Long adminTelegramId, Long targetTelegramId) {
        User adminUser = userRepository.findByTelegramId(adminTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin Admin."));

        authorizationService.requireAdmin(adminUser);

        User targetUser = userRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));

        if (targetUser.getRole() == Role.ADMIN) {
            throw new BusinessException("Không thể thay đổi quyền của ADMIN.");
        }

        if (targetUser.getRole() == Role.MEMBER) {
            log.info("User {} is already MEMBER", targetTelegramId);
            return targetUser;
        }

        Role oldRole = targetUser.getRole();
        targetUser.setRole(Role.MEMBER);
        User updatedUser = userRepository.save(targetUser);
        auditLogService.logAction(adminTelegramId, AuditAction.ADMIN_REVOKE_LANDLORD, "Thu hồi quyền LANDLORD của user " + targetTelegramId);

        eventPublisher.publishEvent(new com.boardinghousebot.observer.RoleChangedEvent(updatedUser, oldRole, Role.MEMBER));

        return updatedUser;
    }

    @Transactional
    public User updateUser(User user) {
        if (user == null || user.getId() == null) {
            throw new BusinessException("Thông tin người dùng không hợp lệ.");
        }
        return userRepository.save(user);
    }

    private boolean isTelegramAdmin(Long telegramId) {
        if (telegramId == null || telegramConfig.getAdminId() == null) {
            return false;
        }
        String configuredAdminId = telegramConfig.getAdminId().trim();
        return !configuredAdminId.isEmpty() && configuredAdminId.equals(telegramId.toString());
    }
}
