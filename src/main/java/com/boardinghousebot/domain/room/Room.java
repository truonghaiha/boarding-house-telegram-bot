package com.boardinghousebot.domain.room;

import com.boardinghousebot.domain.user.User;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_number", nullable = false, unique = true)
    private String roomNumber;

    @Column(name = "monthly_rent", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyRent;

    @Column(name = "area", precision = 10, scale = 2)
    private BigDecimal area;

    @Column(name = "required_deposit", precision = 12, scale = 2)
    private BigDecimal requiredDeposit;

    public BigDecimal getArea() { return area; }
    public void setArea(BigDecimal area) { this.area = area; }
    public BigDecimal getRequiredDeposit() { return requiredDeposit; }
    public void setRequiredDeposit(BigDecimal requiredDeposit) { this.requiredDeposit = requiredDeposit; }

    @Column(name = "max_occupants", nullable = false)
    private Integer maxOccupants;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RoomStatus status;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    private List<User> tenants = new ArrayList<>();

    public Room() {
    }

    public Room(String roomNumber, BigDecimal monthlyRent, Integer maxOccupants, String description) {
        this.roomNumber = roomNumber;
        this.monthlyRent = monthlyRent;
        this.maxOccupants = maxOccupants;
        this.description = description;
        this.status = RoomStatus.AVAILABLE;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = RoomStatus.AVAILABLE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public void setRoomNumber(String roomNumber) {
        this.roomNumber = roomNumber;
    }

    public BigDecimal getMonthlyRent() {
        return monthlyRent;
    }

    public void setMonthlyRent(BigDecimal monthlyRent) {
        this.monthlyRent = monthlyRent;
    }

    public Integer getMaxOccupants() {
        return maxOccupants;
    }

    public void setMaxOccupants(Integer maxOccupants) {
        this.maxOccupants = maxOccupants;
    }

    public RoomStatus getStatus() {
        return status;
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public List<User> getTenants() {
        return tenants;
    }

    public void setTenants(List<User> tenants) {
        this.tenants = tenants;
    }

    public boolean isFull(int currentOccupants) {
        return currentOccupants >= maxOccupants;
    }

    public boolean isFull(long currentOccupants) {
        return currentOccupants >= maxOccupants;
    }

    public boolean hasAvailableCapacity(int currentOccupants) {
        return currentOccupants < maxOccupants;
    }

    public boolean hasAvailableCapacity(long currentOccupants) {
        return currentOccupants < maxOccupants;
    }
}
