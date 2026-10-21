package com.boardinghousebot.domain.meter;
import com.boardinghousebot.domain.contract.Contract;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.*;
@Entity @Getter @Setter
@Table(name="utility_cycles", uniqueConstraints=@UniqueConstraint(columnNames={"contract_id","period_start","baseline"}))
public class UtilityCycle {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Version private Long version;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="contract_id",nullable=false) private Contract contract;
    @Column(name="period_start",nullable=false) private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate plannedEnd;
    private LocalDate readingDate;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime finalizedAt;
    @Column(nullable=false) private boolean baseline;
    @Column(nullable=false) private boolean finalized;
    @Column(nullable=false) private boolean checkout;
    @Column(nullable=false) private boolean adjusted;
    @Column(precision=12,scale=2) private BigDecimal previousElectricity;
    @Column(precision=12,scale=2) private BigDecimal previousWater;
    @Column(precision=12,scale=2) private BigDecimal electricity;
    @Column(precision=12,scale=2) private BigDecimal water;
    @Column(precision=12,scale=2) private BigDecimal electricityPrice;
    @Column(precision=12,scale=2) private BigDecimal waterPrice;
    @Column(precision=12,scale=2) private BigDecimal rent;
    public BigDecimal electricityAmount() { return electricity.subtract(previousElectricity).multiply(electricityPrice); }
    public BigDecimal waterAmount() { return water.subtract(previousWater).multiply(waterPrice); }
    public String periodLabel() {
        var f = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");
        return periodStart.format(f) + " – " + periodEnd.format(f);
    }
}
