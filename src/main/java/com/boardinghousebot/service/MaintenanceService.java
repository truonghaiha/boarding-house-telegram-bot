package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.domain.maintenance.state.MaintenanceState;
import com.boardinghousebot.domain.maintenance.state.MaintenanceStateFactory;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.observer.MaintenanceCreatedEvent;
import com.boardinghousebot.observer.MaintenanceStatusChangedEvent;
import com.boardinghousebot.repository.MaintenanceRequestRepository;
import com.boardinghousebot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);

    private final MaintenanceRequestRepository maintenanceRequestRepository;
    private final UserRepository userRepository;
    private final RoomStayService roomStayService;
    private final AuthorizationService authorizationService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    public MaintenanceService(MaintenanceRequestRepository maintenanceRequestRepository,
                              UserRepository userRepository,
                              RoomStayService roomStayService,
                              AuthorizationService authorizationService,
                              AuditLogService auditLogService,
                              ApplicationEventPublisher eventPublisher) {
        this.maintenanceRequestRepository = maintenanceRequestRepository;
        this.userRepository = userRepository;
        this.roomStayService = roomStayService;
        this.authorizationService = authorizationService;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public MaintenanceRequest createMaintenanceRequest(Long memberTelegramId, String title, String description) {
        User member = userRepository.findByTelegramId(memberTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        authorizationService.requireMember(member);

        Room room = roomStayService.getCurrentRoom(memberTelegramId).orElse(null);

        if (room == null) {
            throw new BusinessException("Bạn chưa thuê phòng nào trong hệ thống nên không thể gửi yêu cầu sự cố.");
        }

        if (title == null || title.trim().isEmpty()) {
            throw new BusinessException("Tiêu đề yêu cầu sự cố không được để trống.");
        }
        if (title.trim().length() > 100) {
            throw new BusinessException("Tiêu đề yêu cầu sự cố không được vượt quá 100 ký tự.");
        }

        if (description == null || description.trim().isEmpty()) {
            throw new BusinessException("Mô tả sự cố không được để trống.");
        }
        if (description.trim().length() > 500) {
            throw new BusinessException("Mô tả sự cố không được vượt quá 500 ký tự.");
        }

        MaintenanceRequest request = new MaintenanceRequest(member, room, title.trim(), description.trim());
        MaintenanceRequest saved = maintenanceRequestRepository.save(request);

        auditLogService.logAction(memberTelegramId, AuditAction.MEMBER_CREATE_MAINTENANCE,
                String.format("Member %s (%d) gửi yêu cầu bảo trì #%d cho phòng %s: %s",
                        member.getFullName(), memberTelegramId, saved.getId(), room.getRoomNumber(), saved.getTitle()));

        log.info("Publishing MaintenanceCreatedEvent for Maintenance ID={}, Member Telegram ID={}", saved.getId(), memberTelegramId);
        eventPublisher.publishEvent(new MaintenanceCreatedEvent(saved));

        log.info("Created MaintenanceRequest ID={} for Room={}", saved.getId(), room.getRoomNumber());
        return saved;
    }

    @Transactional
    public MaintenanceRequest updateMaintenanceStatus(Long landlordTelegramId, Long requestId, MaintenanceStatus newStatus, String landlordNote) {
        User landlord = userRepository.findByTelegramId(landlordTelegramId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy thông tin người dùng."));
        authorizationService.requireLandlord(landlord);

        MaintenanceRequest request = maintenanceRequestRepository.findByIdWithDetails(requestId)
                .orElseThrow(() -> new BusinessException("Không tìm thấy yêu cầu sự cố #" + requestId));

        MaintenanceStatus oldStatus = request.getStatus();

        MaintenanceState state = MaintenanceStateFactory.getState(oldStatus);
        state.updateStatus(request, newStatus, landlordNote);

        MaintenanceRequest saved = maintenanceRequestRepository.save(request);

        auditLogService.logAction(landlordTelegramId, AuditAction.LANDLORD_UPDATE_MAINTENANCE_STATUS,
                String.format("Landlord %d cập nhật trạng thái yêu cầu bảo trì #%d từ %s sang %s. Ghi chú: %s",
                        landlordTelegramId, requestId, oldStatus.getDisplayName(), newStatus.getDisplayName(), landlordNote != null ? landlordNote : "Không có"));

        log.info("Publishing MaintenanceStatusChangedEvent for Maintenance ID={}, OldStatus={}, NewStatus={}", saved.getId(), oldStatus, newStatus);
        eventPublisher.publishEvent(new MaintenanceStatusChangedEvent(saved, oldStatus, newStatus));

        log.info("Updated MaintenanceRequest ID={} status from {} to {}", requestId, oldStatus, newStatus);
        return saved;
    }

    @Transactional(readOnly = true)
    public MaintenanceRequest getMaintenanceRequestById(Long id) {
        return maintenanceRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new BusinessException("Không tìm thấy yêu cầu sự cố #" + id));
    }

    @Transactional(readOnly = true)
    public MaintenanceRequest getMaintenanceForUser(User viewer, Long requestId) {
        MaintenanceRequest request = getMaintenanceRequestById(requestId);
        if (viewer.getRole() != com.boardinghousebot.domain.user.Role.LANDLORD) {
            authorizationService.requireMember(viewer);
            if (!viewer.getTelegramId().equals(request.getMember().getTelegramId())) {
                throw new BusinessException("Bạn không có quyền xem yêu cầu sự cố này.");
            }
        }
        return request;
    }

    @Transactional(readOnly = true)
    public List<MaintenanceRequest> getAllMaintenanceRequests() {
        return maintenanceRequestRepository.findAllWithDetails();
    }

}
