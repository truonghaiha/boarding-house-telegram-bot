package com.boardinghousebot.repository;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.contract.ContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ContractRepository extends JpaRepository<Contract, Long> {

    @Query("SELECT c FROM Contract c LEFT JOIN FETCH c.member m LEFT JOIN FETCH m.room LEFT JOIN FETCH c.room r WHERE c.id = :id")
    Optional<Contract> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT c FROM Contract c LEFT JOIN FETCH c.member m LEFT JOIN FETCH m.room LEFT JOIN FETCH c.room r ORDER BY c.id DESC")
    List<Contract> findAllWithDetails();

    @Query("SELECT c FROM Contract c LEFT JOIN FETCH c.member m LEFT JOIN FETCH m.room LEFT JOIN FETCH c.room r WHERE c.status = :status ORDER BY c.id DESC")
    List<Contract> findByStatusWithDetails(@Param("status") ContractStatus status);

    @Query("SELECT c FROM Contract c LEFT JOIN FETCH c.member m LEFT JOIN FETCH m.room LEFT JOIN FETCH c.room r WHERE m.telegramId = :telegramId AND c.status = 'ACTIVE'")
    Optional<Contract> findActiveContractByMemberTelegramId(@Param("telegramId") Long telegramId);

    @Query("SELECT c FROM Contract c LEFT JOIN FETCH c.member m LEFT JOIN FETCH m.room LEFT JOIN FETCH c.room r WHERE r.id = :roomId AND c.status = 'ACTIVE'")
    Optional<Contract> findActiveContractByRoomId(@Param("roomId") Long roomId);

    @Query("SELECT c FROM Contract c LEFT JOIN FETCH c.member m LEFT JOIN FETCH m.room LEFT JOIN FETCH c.room r WHERE c.status = 'ACTIVE' AND c.endDate BETWEEN :startDate AND :endDate ORDER BY c.endDate ASC")
    List<Contract> findExpiringContracts(@Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    boolean existsByMemberTelegramIdAndStatus(Long memberTelegramId, ContractStatus status);

    boolean existsByRoomIdAndStatus(Long roomId, ContractStatus status);
}
