package com.boardinghousebot.strategy;

import com.boardinghousebot.domain.meter.UtilityType;

import java.math.BigDecimal;

public interface UtilityPricingStrategy {

    UtilityType getSupportedUtilityType();

    BigDecimal calculateCharge(BigDecimal consumption, BigDecimal unitPrice);
}
