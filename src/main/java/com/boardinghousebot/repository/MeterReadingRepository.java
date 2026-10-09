package com.boardinghousebot.repository;

import com.boardinghousebot.domain.meter.MeterReading;
import com.boardinghousebot.domain.meter.UtilityType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MeterReadingRepository extends JpaRepository<MeterReading, Long> {

    @Query("SELECT m FROM MeterReading m LEFT JOIN FETCH m.room r WHERE m.id = :id")
    Optional<MeterReading> findByIdWithDetails(@Param("id") Long id);

    @Query("SELECT m FROM MeterReading m LEFT JOIN FETCH m.room r ORDER BY m.billingYear DESC, m.billingMonth DESC, r.roomNumber ASC")
    List<MeterReading> findAllWithDetails();

    @Query("SELECT m FROM MeterReading m LEFT JOIN FETCH m.room r WHERE r.id = :roomId ORDER BY m.billingYear DESC, m.billingMonth DESC")
    List<MeterReading> findByRoomIdWithDetails(@Param("roomId") Long roomId);

    @Query("SELECT m FROM MeterReading m LEFT JOIN FETCH m.room r WHERE m.utilityType = :utilityType ORDER BY m.billingYear DESC, m.billingMonth DESC, r.roomNumber ASC")
    List<MeterReading> findByUtilityTypeWithDetails(@Param("utilityType") UtilityType utilityType);

    @Query("SELECT m FROM MeterReading m LEFT JOIN FETCH m.room r WHERE r.id = :roomId AND m.utilityType = :utilityType ORDER BY m.billingYear DESC, m.billingMonth DESC")
    List<MeterReading> findByRoomIdAndUtilityTypeWithDetails(@Param("roomId") Long roomId, @Param("utilityType") UtilityType utilityType);

    @Query("SELECT m FROM MeterReading m LEFT JOIN FETCH m.room r WHERE r.id = :roomId AND m.utilityType = :utilityType AND m.billingMonth = :month AND m.billingYear = :year")
    Optional<MeterReading> findByRoomIdAndUtilityTypeAndBillingMonthAndBillingYear(
            @Param("roomId") Long roomId,
            @Param("utilityType") UtilityType utilityType,
            @Param("month") Integer month,
            @Param("year") Integer year
    );

    boolean existsByRoomIdAndUtilityTypeAndBillingMonthAndBillingYear(Long roomId, UtilityType utilityType, Integer month, Integer year);

    @Query("SELECT DISTINCT m.billingYear, m.billingMonth FROM MeterReading m ORDER BY m.billingYear DESC, m.billingMonth DESC")
    List<Object[]> findDistinctBillingPeriods();

    @Query("SELECT m FROM MeterReading m LEFT JOIN FETCH m.room r " +
           "WHERE (:roomId IS NULL OR r.id = :roomId) " +
           "AND (:utilityType IS NULL OR m.utilityType = :utilityType) " +
           "AND (:month IS NULL OR m.billingMonth = :month) " +
           "AND (:year IS NULL OR m.billingYear = :year) " +
           "ORDER BY m.billingYear DESC, m.billingMonth DESC, r.roomNumber ASC, m.utilityType ASC")
    List<MeterReading> filterReadingsWithDetails(
            @Param("roomId") Long roomId,
            @Param("utilityType") UtilityType utilityType,
            @Param("month") Integer month,
            @Param("year") Integer year
    );
}
