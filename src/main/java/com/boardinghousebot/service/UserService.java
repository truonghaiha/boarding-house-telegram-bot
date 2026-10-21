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

    private final com.boardinghousebot.repository.ContractRepository contractRepository;
    private final com.boardinghousebot.state.ConversationStateService stateService;
    private final UserRepository userRepository;
    private final TelegramConfig telegramConfig;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public UserService(com.boardinghousebot.state.ConversationStateService stateService, com.boardinghousebot.repository.ContractRepository contractRepository, UserRepository userRepository,
                       TelegramConfig telegramConfig,
                       AuthorizationService authorizationService,
                       AuditLogService auditLogService,
                       org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.contractRepository = contractRepository;
        this.stateService = stateService;
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
        boolean isAdminConfigured = telegramConfig.isConfiguredAdmin(telegramId);

        if (existingUserOpt.isPresent()) {
            User user = existingUserOpt.get();
            boolean updated = false;

            if (isAdminConfigured && user.getRole() != Role.ADMIN) {
                requireAdminEligibility(user);
                changeConfiguredRole(user, Role.ADMIN);
                updated = true;
            } else if (!isAdminConfigured && user.getRole() == Role.ADMIN) {
                changeConfiguredRole(user, Role.MEMBER);
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

    @Transactional
    public User updateTenantPhoneNumber(Long landlordTelegramId, Long targetTelegramId, String phoneNumber) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người dùng."));
        authorizationService.requireLandlord(landlord);
        User tenant = userRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy người thuê."));
        if (tenant.getRole() != Role.MEMBER || tenant.getRoom() == null)
            throw new BusinessException("Chỉ có thể sửa số điện thoại của người thuê đang được xếp phòng.");
        tenant.setPhoneNumber(com.boardinghousebot.util.PhoneNumberUtil.normalizeRequired(phoneNumber));
        User saved = userRepository.save(tenant);
        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_UPDATE_TENANT_CONTACT,
                "Cập nhật số điện thoại người thuê " + targetTelegramId);
        return saved;
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

    @Transactional(readOnly = true)
    public List<User> searchUsersAsAdmin(User actor, String query) {
        authorizationService.requireAdmin(actor);
        if (query == null || query.isBlank()) return List.of();
        String normalized = query.trim();
        if (normalized.matches("\\d+")) {
            try { return findByTelegramId(Long.parseLong(normalized)).stream().toList(); }
            catch (NumberFormatException ignored) { return List.of(); }
        }
        return searchUsers(normalized.startsWith("@") ? normalized.substring(1) : normalized);
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

        if (targetUser.getRoom() != null || contractRepository.existsByMemberTelegramIdAndStatus(targetTelegramId, com.boardinghousebot.domain.contract.ContractStatus.ACTIVE)) {
            throw new BusinessException("Cần kết thúc hợp đồng và đưa người thuê ra khỏi phòng trước khi cấp quyền LANDLORD.");
        }
        Role oldRole = targetUser.getRole();
        targetUser.setRole(Role.LANDLORD);
        User updatedUser = userRepository.save(targetUser);
        stateService.clearState(targetTelegramId);
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

        if (targetUser.getRoom() != null || contractRepository.existsByMemberTelegramIdAndStatus(targetTelegramId, com.boardinghousebot.domain.contract.ContractStatus.ACTIVE)) {
            throw new BusinessException("Tài khoản còn liên kết thuê phòng cần được xử lý trước khi thu hồi quyền LANDLORD.");
        }
        Role oldRole = targetUser.getRole();
        targetUser.setRole(Role.MEMBER);
        User updatedUser = userRepository.save(targetUser);
        stateService.clearState(targetTelegramId);
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

    @Transactional
    public void synchronizeConfiguredAdmin() {
        Long configuredId = telegramConfig.getRequiredAdminTelegramId();
        Optional<User> configuredUser = userRepository.findByTelegramIdWithRoom(configuredId);
        // Validate before changing any account; failure rolls back the entire transfer.
        configuredUser.ifPresent(this::requireAdminEligibility);
        for (User admin : userRepository.findByRole(Role.ADMIN)) {
            if (!configuredId.equals(admin.getTelegramId())) {
                changeConfiguredRole(admin, Role.MEMBER);
            }
        }
        configuredUser.filter(user -> user.getRole() != Role.ADMIN)
                .ifPresent(user -> changeConfiguredRole(user, Role.ADMIN));
    }

    private void requireAdminEligibility(User user) {
        if (user.getRoom() != null || contractRepository.existsByMemberTelegramIdAndStatus(
                user.getTelegramId(), com.boardinghousebot.domain.contract.ContractStatus.ACTIVE)) {
            throw new BusinessException("Tài khoản cấu hình ADMIN còn liên kết thuê phòng; cần xử lý tình trạng thuê trước khi đổi vai trò.");
        }
    }

    private void changeConfiguredRole(User user, Role newRole) {
        Role oldRole = user.getRole();
        user.setRole(newRole);
        userRepository.save(user);
        stateService.clearState(user.getTelegramId());
        auditLogService.logAction(user.getTelegramId(),
                newRole == Role.ADMIN ? AuditAction.CONFIG_GRANT_ADMIN : AuditAction.CONFIG_REVOKE_ADMIN,
                "Đồng bộ TELEGRAM_ADMIN_ID: " + oldRole + " -> " + newRole);
    }
}
