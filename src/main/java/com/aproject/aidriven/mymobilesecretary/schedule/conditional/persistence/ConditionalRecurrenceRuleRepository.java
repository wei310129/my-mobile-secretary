package com.aproject.aidriven.mymobilesecretary.schedule.conditional.persistence;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.ConditionalRecurrenceRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConditionalRecurrenceRuleRepository
        extends JpaRepository<ConditionalRecurrenceRule, Long> {
}
