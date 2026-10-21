package com.boardinghousebot.repository;

import com.boardinghousebot.domain.meter.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface UtilityRateRepository extends JpaRepository<UtilityRate, Long> {
    Optional<UtilityRate> findFirstByUtilityTypeAndEffectiveDateLessThanEqualOrderByEffectiveDateDescIdDesc(UtilityType type, LocalDate date);
}
