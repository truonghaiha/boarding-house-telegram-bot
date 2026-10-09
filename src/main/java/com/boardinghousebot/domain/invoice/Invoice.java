package com.boardinghousebot.domain.invoice;

import com.boardinghousebot.domain.contract.Contract;
import com.boardinghousebot.domain.room.Room;
import com.boardinghousebot.domain.user.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "invoices",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_invoice_room_period", columnNames = {"room_id", "billing_month", "billing_year"})
        },
        indexes = {
                @Index(name = "idx_invoice_room_period_unique", columnList = "room_id, billing_month, billing_year", unique = true)
        }
)
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private User member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id")
    private Contract contract;

    @Column(name = "billing_month", nullable = false)
    private Integer billingMonth;

    @Column(name = "billing_year", nullable = false)
    private Integer billingYear;

    @Column(name = "room_rent_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal roomRentAmount;

    @Column(name = "electricity_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal electricityAmount;

    @Column(name = "water_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal waterAmount;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private InvoiceStatus status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Invoice() {
    }

    public Invoice(Room room, User member, Contract contract, Integer billingMonth, Integer billingYear,
                   BigDecimal roomRentAmount, BigDecimal electricityAmount, BigDecimal waterAmount,
                   BigDecimal totalAmount, LocalDate dueDate) {
        this.room = room;
        this.member = member;
        this.contract = contract;
        this.billingMonth = billingMonth;
        this.billingYear = billingYear;
        this.roomRentAmount = roomRentAmount;
        this.electricityAmount = electricityAmount;
        this.waterAmount = waterAmount;
        this.totalAmount = totalAmount;
        this.paidAmount = BigDecimal.ZERO;
        this.status = InvoiceStatus.UNPAID;
        this.dueDate = dueDate;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
        if (paidAmount == null) {
            paidAmount = BigDecimal.ZERO;
        }
        if (status == null) {
            status = InvoiceStatus.UNPAID;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public BigDecimal getRemainingAmount() {
        if (totalAmount == null) return BigDecimal.ZERO;
        BigDecimal paid = paidAmount != null ? paidAmount : BigDecimal.ZERO;
        BigDecimal remaining = totalAmount.subtract(paid);
        return remaining.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : remaining;
    }

    public InvoiceStatus calculateCurrentStatus() {
        BigDecimal paid = paidAmount != null ? paidAmount : BigDecimal.ZERO;
        if (totalAmount != null && paid.compareTo(totalAmount) >= 0) {
            return InvoiceStatus.PAID;
        }
        if (dueDate != null && dueDate.isBefore(LocalDate.now())) {
            return InvoiceStatus.OVERDUE;
        }
        if (paid.compareTo(BigDecimal.ZERO) > 0) {
            return InvoiceStatus.PARTIALLY_PAID;
        }
        return InvoiceStatus.UNPAID;
    }

    public void updateStatusBasedOnDueDate() {
        this.status = calculateCurrentStatus();
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

    public User getMember() {
        return member;
    }

    public void setMember(User member) {
        this.member = member;
    }

    public Contract getContract() {
        return contract;
    }

    public void setContract(Contract contract) {
        this.contract = contract;
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

    public BigDecimal getRoomRentAmount() {
        return roomRentAmount;
    }

    public void setRoomRentAmount(BigDecimal roomRentAmount) {
        this.roomRentAmount = roomRentAmount;
    }

    public BigDecimal getElectricityAmount() {
        return electricityAmount;
    }

    public void setElectricityAmount(BigDecimal electricityAmount) {
        this.electricityAmount = electricityAmount;
    }

    public BigDecimal getWaterAmount() {
        return waterAmount;
    }

    public void setWaterAmount(BigDecimal waterAmount) {
        this.waterAmount = waterAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(InvoiceStatus status) {
        this.status = status;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
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
