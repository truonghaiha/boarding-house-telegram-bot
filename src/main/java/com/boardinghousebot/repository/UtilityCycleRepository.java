package com.boardinghousebot.repository;
import com.boardinghousebot.domain.meter.UtilityCycle;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface UtilityCycleRepository extends JpaRepository<UtilityCycle,Long> {
    @Query("select c from UtilityCycle c join fetch c.contract k join fetch k.room join fetch k.member where c.id=:id")
    Optional<UtilityCycle> details(Long id);
    @Query("select c from UtilityCycle c join fetch c.contract k join fetch k.room join fetch k.member order by c.periodStart desc,c.id desc")
    List<UtilityCycle> allDetails();
    List<UtilityCycle> findByContractIdOrderByIdDesc(Long contractId);
}
