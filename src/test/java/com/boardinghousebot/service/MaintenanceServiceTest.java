package com.boardinghousebot.service;

import com.boardinghousebot.domain.audit.AuditAction;
import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import com.boardinghousebot.observer.MaintenanceCreatedEvent;
import com.boardinghousebot.observer.MaintenanceStatusChangedEvent;
import com.boardinghousebot.repository.AuditLogRepository;
import com.boardinghousebot.repository.ContractRepository;
import com.boardinghousebot.repository.MaintenanceRequestRepository;
import com.boardinghousebot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MaintenanceServiceTest {

    @Mock
    private MaintenanceRequestRepository maintenanceRequestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContractRepository contractRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AuthorizationService authorizationService;
    private AuditLogService auditLogService;
    private MaintenanceService maintenanceService;

    private User member;
    private User landlord;
    private Room room;
    private Contract activeContract;
    private MaintenanceRequest pendingRequest;

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationService();
        auditLogService = new AuditLogService(auditLogRepository);
        maintenanceService = new MaintenanceService(
                maintenanceRequestRepository,
                userRepository,
                contractRepository,
                authorizationService,
                auditLogService,
                eventPublisher
        );

        member = new User(100L, "member", "Nguyen Van A", Role.MEMBER);
        landlord = new User(200L, "landlord", "Tran Van B", Role.LANDLORD);
        room = new Room("101", new BigDecimal("3000000"), 2, "Phong Dep");
        member.setRoom(room);

        activeContract = new Contract(member, room, LocalDate.now(), LocalDate.now().plusMonths(6), new BigDecimal("3000000"), new BigDecimal("3000000"));
        pendingRequest = new MaintenanceRequest(member, room, "Hỏng điện", "Cầu chì bị ngắt");
    }

    @Test
    @DisplayName("createMaintenanceRequest successfully creates request and publishes event")
    void createMaintenanceRequest_Success() {
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(member));
        when(contractRepository.findActiveContractByMemberTelegramId(100L)).thenReturn(Optional.of(activeContract));
        when(maintenanceRequestRepository.save(any(MaintenanceRequest.class))).thenAnswer(i -> i.getArgument(0));

        MaintenanceRequest result = maintenanceService.createMaintenanceRequest(100L, " Hỏng quạt ", " Quạt trần kêu to ");

        assertNotNull(result);
        assertEquals("Hỏng quạt", result.getTitle());
        assertEquals("Quạt trần kêu to", result.getDescription());
        assertEquals(MaintenanceStatus.PENDING, result.getStatus());

        verify(auditLogRepository).save(argThat(log -> log.getAction().equals(AuditAction.MEMBER_CREATE_MAINTENANCE)));
        verify(eventPublisher).publishEvent(any(MaintenanceCreatedEvent.class));
    }

    @Test
    @DisplayName("createMaintenanceRequest throws exception when user has no room")
    void createMaintenanceRequest_NoRoom_ThrowsException() {
        member.setRoom(null);
        when(userRepository.findByTelegramId(100L)).thenReturn(Optional.of(member));
        when(contractRepository.findActiveContractByMemberTelegramId(100L)).thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                maintenanceService.createMaintenanceRequest(100L, "Hỏng điện", "Mô tả"));

        assertTrue(ex.getMessage().contains("chưa thuê phòng nào"));
    }

    @Test
    @DisplayName("updateMaintenanceStatus successfully changes state and publishes event")
    void updateMaintenanceStatus_Success() {
        when(userRepository.findByTelegramId(200L)).thenReturn(Optional.of(landlord));
        when(maintenanceRequestRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(pendingRequest));
        when(maintenanceRequestRepository.save(any(MaintenanceRequest.class))).thenAnswer(i -> i.getArgument(0));

        MaintenanceRequest updated = maintenanceService.updateMaintenanceStatus(200L, 1L, MaintenanceStatus.IN_PROGRESS, "Đã gọi thợ");

        assertEquals(MaintenanceStatus.IN_PROGRESS, updated.getStatus());
        assertEquals("Đã gọi thợ", updated.getLandlordNote());

        verify(auditLogRepository).save(argThat(log -> log.getAction().equals(AuditAction.LANDLORD_UPDATE_MAINTENANCE_STATUS)));
        verify(eventPublisher).publishEvent(any(MaintenanceStatusChangedEvent.class));
    }

    @Test
    @DisplayName("updateMaintenanceStatus throws exception when invalid state transition attempted")
    void updateMaintenanceStatus_InvalidTransition_ThrowsException() {
        pendingRequest.setStatus(MaintenanceStatus.RESOLVED);
        when(userRepository.findByTelegramId(200L)).thenReturn(Optional.of(landlord));
        when(maintenanceRequestRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(pendingRequest));

        assertThrows(BusinessException.class, () ->
                maintenanceService.updateMaintenanceStatus(200L, 1L, MaintenanceStatus.IN_PROGRESS, "Revert"));
    }
}
