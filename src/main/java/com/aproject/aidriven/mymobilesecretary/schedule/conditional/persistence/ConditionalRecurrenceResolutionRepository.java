package com.aproject.aidriven.mymobilesecretary.schedule.conditional.persistence;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceResolution;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConditionalRecurrenceResolutionRepository
        extends JpaRepository<ConditionalRecurrenceResolution, Long> {

    Optional<ConditionalRecurrenceResolution> findByRuleIdAndBaseDate(
            Long ruleId, LocalDate baseDate);
}
