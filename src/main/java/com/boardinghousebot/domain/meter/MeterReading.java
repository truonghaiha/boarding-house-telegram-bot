package com.boardinghousebot.domain.meter;

import com.boardinghousebot.domain.room.Room;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "meter_readings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_meter_reading_room_type_period", columnNames = {"room_id", "utility_type", "billing_month", "billing_year"})
        },
        indexes = {
                @Index(name = "idx_meter_reading_room_type_period_unique", columnList = "room_id, utility_type, billing_month, billing_year", unique = true)
        }
)
public class MeterReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @Enumerated(EnumType.STRING)
    @Column(name = "utility_type", nullable = false)
    private UtilityType utilityType;

    @Column(name = "billing_month", nullable = false)
    private Integer billingMonth;

    @Column(name = "billing_year", nullable = false)
    private Integer billingYear;

    @Column(name = "previous_reading", nullable = false, precision = 12, scale = 2)
    private BigDecimal previousReading;

    @Column(name = "current_reading", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentReading;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public MeterReading() {
    }

    public MeterReading(Room room, UtilityType utilityType, Integer billingMonth, Integer billingYear,
                        BigDecimal previousReading, BigDecimal currentReading, BigDecimal unitPrice) {
        this.room = room;
        this.utilityType = utilityType;
        this.billingMonth = billingMonth;
        this.billingYear = billingYear;
        this.previousReading = previousReading;
        this.currentReading = currentReading;
        this.unitPrice = unitPrice;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public BigDecimal getConsumption() {
        if (currentReading == null || previousReading == null) {
            return BigDecimal.ZERO;
        }
        return currentReading.subtract(previousReading);
    }

    public BigDecimal getTotalCharge() {
        BigDecimal consumption = getConsumption();
        if (consumption == null || unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return consumption.multiply(unitPrice);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    public UtilityType getUtilityType() {
        return utilityType;
    }

    public void setUtilityType(UtilityType utilityType) {
        this.utilityType = utilityType;
    }

    public Integer getBillingMonth() {
        return billingMonth;
    }

    public void setBillingMonth(Integer billingMonth) {
        this.billingMonth = billingMonth;
    }

    public Integer getBillingYear() {
        return billingYear;
    }

    public void setBillingYear(Integer billingYear) {
        this.billingYear = billingYear;
    }

    public BigDecimal getPreviousReading() {
        return previousReading;
    }

    public void setPreviousReading(BigDecimal previousReading) {
        this.previousReading = previousReading;
    }

    public BigDecimal getCurrentReading() {
        return currentReading;
    }

    public void setCurrentReading(BigDecimal currentReading) {
        this.currentReading = currentReading;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
