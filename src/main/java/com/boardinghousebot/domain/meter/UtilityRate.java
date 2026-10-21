package com.boardinghousebot.domain.meter;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "utility_rates")
public class UtilityRate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private UtilityType utilityType;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;
    @Column(nullable = false)
    private LocalDate effectiveDate;
    protected UtilityRate() {}
    public UtilityRate(UtilityType type, BigDecimal price, LocalDate date) {
        utilityType = type; unitPrice = price; effectiveDate = date;
    }
    public Long getId() { return id; }
    public UtilityType getUtilityType() { return utilityType; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
}
