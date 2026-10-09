package com.boardinghousebot.repository;

import com.boardinghousebot.domain.maintenance.MaintenanceRequest;
import com.boardinghousebot.domain.maintenance.MaintenanceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaintenanceRequestRepository extends JpaRepository<MaintenanceRequest, Long> {

    @Query("SELECT m FROM MaintenanceRequest m LEFT JOIN FETCH m.room r LEFT JOIN FETCH m.member u WHERE m.id = :id")
    Optional<MaintenanceRequest> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT m FROM MaintenanceRequest m LEFT JOIN FETCH m.room r LEFT JOIN FETCH m.member u ORDER BY m.createdAt DESC")
    List<MaintenanceRequest> findAllWithDetails();

    @Query("SELECT m FROM MaintenanceRequest m LEFT JOIN FETCH m.room r LEFT JOIN FETCH m.member u WHERE u.telegramId = :telegramId ORDER BY m.createdAt DESC")
    List<MaintenanceRequest> findByMemberTelegramIdWithDetails(@Param("telegramId") Long telegramId);

    @Query("SELECT m FROM MaintenanceRequest m LEFT JOIN FETCH m.room r LEFT JOIN FETCH m.member u WHERE r.id = :roomId ORDER BY m.createdAt DESC")
    List<MaintenanceRequest> findByRoomIdWithDetails(@Param("roomId") Long roomId);

    @Query("SELECT m FROM MaintenanceRequest m LEFT JOIN FETCH m.room r LEFT JOIN FETCH m.member u WHERE m.status = :status ORDER BY m.createdAt DESC")
    List<MaintenanceRequest> findByStatusWithDetails(@Param("status") MaintenanceStatus status);
}
