package com.boardinghousebot.domain.maintenance;

import com.boardinghousebot.domain.maintenance.state.InProgressState;
import com.boardinghousebot.domain.maintenance.state.MaintenanceState;
import com.boardinghousebot.domain.maintenance.state.MaintenanceStateFactory;
import com.boardinghousebot.domain.maintenance.state.PendingState;
import com.boardinghousebot.domain.maintenance.state.ResolvedState;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.Role;
import com.boardinghousebot.domain.user.User;
import com.boardinghousebot.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceStatePatternTest {

    private User member;
    private Room room;
    private MaintenanceRequest request;

    @BeforeEach
    void setUp() {
        member = new User(100L, "member1", "Nguyen Van A", Role.MEMBER);
        room = new Room("101", new java.math.BigDecimal("3000000"), 2, "Phong standard");
        request = new MaintenanceRequest(member, room, "Hỏng điện", "Ổ cắm bị chập điện");
    }

    @Test
    @DisplayName("Initial status is PENDING and state factory returns PendingState")
    void initialStatus_ShouldBePending() {
        assertEquals(MaintenanceStatus.PENDING, request.getStatus());
        MaintenanceState state = MaintenanceStateFactory.getState(request.getStatus());
        assertInstanceOf(PendingState.class, state);
    }

    @Test
    @DisplayName("PENDING state allows transition to IN_PROGRESS")
    void pendingState_TransitionToInProgress_ShouldSucceed() {
        MaintenanceState state = MaintenanceStateFactory.getState(request.getStatus());
        state.updateStatus(request, MaintenanceStatus.IN_PROGRESS, "Đã hẹn thợ");

        assertEquals(MaintenanceStatus.IN_PROGRESS, request.getStatus());
        assertEquals("Đã hẹn thợ", request.getLandlordNote());
        assertNull(request.getResolvedAt());
    }

    @Test
    @DisplayName("PENDING state allows transition directly to RESOLVED")
    void pendingState_TransitionToResolved_ShouldSucceed() {
        MaintenanceState state = MaintenanceStateFactory.getState(request.getStatus());
        state.updateStatus(request, MaintenanceStatus.RESOLVED, "Đã sửa xong trực tiếp");

        assertEquals(MaintenanceStatus.RESOLVED, request.getStatus());
        assertEquals("Đã sửa xong trực tiếp", request.getLandlordNote());
        assertNotNull(request.getResolvedAt());
    }

    @Test
    @DisplayName("IN_PROGRESS state allows transition to RESOLVED")
    void inProgressState_TransitionToResolved_ShouldSucceed() {
        request.setStatus(MaintenanceStatus.IN_PROGRESS);
        MaintenanceState state = MaintenanceStateFactory.getState(request.getStatus());
        assertInstanceOf(InProgressState.class, state);

        state.updateStatus(request, MaintenanceStatus.RESOLVED, "Thay công tắc mới");

        assertEquals(MaintenanceStatus.RESOLVED, request.getStatus());
        assertEquals("Thay công tắc mới", request.getLandlordNote());
        assertNotNull(request.getResolvedAt());
    }

    @Test
    @DisplayName("IN_PROGRESS state forbids transition back to PENDING")
    void inProgressState_TransitionToPending_ShouldThrowException() {
        request.setStatus(MaintenanceStatus.IN_PROGRESS);
        MaintenanceState state = MaintenanceStateFactory.getState(request.getStatus());

        BusinessException ex = assertThrows(BusinessException.class, () ->
                state.updateStatus(request, MaintenanceStatus.PENDING, "Revert"));

        assertTrue(ex.getMessage().contains("chỉ được phép chuyển sang"));
    }

    @Test
    @DisplayName("RESOLVED is terminal state and forbids transition back to PENDING or IN_PROGRESS")
    void resolvedState_Transitions_ShouldThrowException() {
        request.setStatus(MaintenanceStatus.RESOLVED);
        MaintenanceState state = MaintenanceStateFactory.getState(request.getStatus());
        assertInstanceOf(ResolvedState.class, state);

        assertThrows(BusinessException.class, () ->
                state.updateStatus(request, MaintenanceStatus.PENDING, "Revert"));

        assertThrows(BusinessException.class, () ->
                state.updateStatus(request, MaintenanceStatus.IN_PROGRESS, "Revert"));

        assertThrows(BusinessException.class, () ->
                state.updateStatus(request, MaintenanceStatus.RESOLVED, "Already resolved"));
    }
}
