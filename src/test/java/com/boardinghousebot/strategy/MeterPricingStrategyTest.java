package com.boardinghousebot.strategy;

import com.boardinghousebot.domain.meter.UtilityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MeterPricingStrategyTest {

    private final ElectricityPricingStrategy electricityStrategy = new ElectricityPricingStrategy();
    private final WaterPricingStrategy waterStrategy = new WaterPricingStrategy();

    @Test
    @DisplayName("Electricity pricing strategy calculates 150 kWh * 3500 VNĐ = 525000 VNĐ")
    void testElectricityPricingStrategy() {
        assertEquals(UtilityType.ELECTRICITY, electricityStrategy.getSupportedUtilityType());

        BigDecimal consumption = new BigDecimal("150");
        BigDecimal unitPrice = new BigDecimal("3500");
        BigDecimal charge = electricityStrategy.calculateCharge(consumption, unitPrice);

        assertEquals(new BigDecimal("525000"), charge);
    }

    @Test
    @DisplayName("Water pricing strategy calculates 12 m³ * 15000 VNĐ = 180000 VNĐ")
    void testWaterPricingStrategy() {
        assertEquals(UtilityType.WATER, waterStrategy.getSupportedUtilityType());

        BigDecimal consumption = new BigDecimal("12");
        BigDecimal unitPrice = new BigDecimal("15000");
        BigDecimal charge = waterStrategy.calculateCharge(consumption, unitPrice);

        assertEquals(new BigDecimal("180000"), charge);
    }

    @Test
    @DisplayName("Pricing strategy handles null or negative inputs gracefully")
    void testInvalidInputs() {
        assertEquals(BigDecimal.ZERO, electricityStrategy.calculateCharge(null, new BigDecimal("3500")));
        assertEquals(BigDecimal.ZERO, electricityStrategy.calculateCharge(new BigDecimal("150"), null));
        assertEquals(BigDecimal.ZERO, electricityStrategy.calculateCharge(new BigDecimal("-10"), new BigDecimal("3500")));
        assertEquals(BigDecimal.ZERO, waterStrategy.calculateCharge(new BigDecimal("12"), new BigDecimal("-1000")));
    }
}
