package com.boardinghousebot.strategy;

import com.boardinghousebot.domain.meter.UtilityType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class ElectricityPricingStrategy implements UtilityPricingStrategy {

    @Override
    public UtilityType getSupportedUtilityType() {
        return UtilityType.ELECTRICITY;
    }

    @Override
    public BigDecimal calculateCharge(BigDecimal consumption, BigDecimal unitPrice) {
        if (consumption == null || unitPrice == null || consumption.compareTo(BigDecimal.ZERO) < 0 || unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return consumption.multiply(unitPrice);
    }
}
