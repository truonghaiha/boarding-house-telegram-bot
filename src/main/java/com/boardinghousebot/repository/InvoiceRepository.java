package com.boardinghousebot.repository;

import com.boardinghousebot.domain.invoice.Invoice;
import com.boardinghousebot.domain.invoice.InvoiceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    Optional<Invoice> findByUtilityCycleId(Long cycleId);
    boolean existsByUtilityCycleId(Long cycleId);
    List<Invoice> findByContractId(Long contractId);

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m LEFT JOIN FETCH i.contract c WHERE i.id = :id")
    Optional<Invoice> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m ORDER BY i.billingYear DESC, i.billingMonth DESC, r.roomNumber ASC")
    List<Invoice> findAllWithDetails();

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m WHERE r.id = :roomId ORDER BY i.billingYear DESC, i.billingMonth DESC")
    List<Invoice> findByRoomIdWithDetails(@Param("roomId") Long roomId);

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m WHERE m.telegramId = :telegramId ORDER BY i.billingYear DESC, i.billingMonth DESC")
    List<Invoice> findByMemberTelegramIdWithDetails(@Param("telegramId") Long telegramId);

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m " +
           "WHERE (:roomId IS NULL OR r.id = :roomId) " +
           "AND (:status IS NULL OR i.status = :status) " +
           "AND (:month IS NULL OR i.billingMonth = :month) " +
           "AND (:year IS NULL OR i.billingYear = :year) " +
           "ORDER BY i.billingYear DESC, i.billingMonth DESC, r.roomNumber ASC")
    List<Invoice> filterInvoicesWithDetails(
            @Param("roomId") Long roomId,
            @Param("status") InvoiceStatus status,
            @Param("month") Integer month,
            @Param("year") Integer year
    );

    @Query("SELECT DISTINCT i.billingYear, i.billingMonth FROM Invoice i ORDER BY i.billingYear DESC, i.billingMonth DESC")
    List<Object[]> findDistinctBillingPeriods();

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m WHERE r.id = :roomId AND i.billingMonth = :month AND i.billingYear = :year AND i.utilityCycleId IS NULL")
    Optional<Invoice> findByRoomIdAndBillingMonthAndBillingYear(
            @Param("roomId") Long roomId,
            @Param("month") Integer month,
            @Param("year") Integer year
    );

    boolean existsByRoomIdAndBillingMonthAndBillingYear(Long roomId, Integer month, Integer year);
    boolean existsByMemberTelegramId(Long memberTelegramId);

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m WHERE i.dueDate = :dueDate AND i.status != 'PAID'")
    List<Invoice> findInvoicesDueOn(@Param("dueDate") java.time.LocalDate dueDate);

    @Query("SELECT i FROM Invoice i LEFT JOIN FETCH i.room r LEFT JOIN FETCH i.member m WHERE i.dueDate < :currentDate AND i.status != 'PAID'")
    List<Invoice> findOverdueInvoices(@Param("currentDate") java.time.LocalDate currentDate);
}
