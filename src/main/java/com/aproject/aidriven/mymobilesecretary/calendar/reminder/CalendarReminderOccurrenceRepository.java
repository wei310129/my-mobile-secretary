package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface CalendarReminderOccurrenceRepository
        extends JpaRepository<CalendarReminderOccurrenceEntity, UUID> {

    List<CalendarReminderOccurrenceEntity>
            findAllByRuleIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                    UUID ruleId,
                    CalendarReminderOccurrenceEntity.Status status,
                    UUID workspaceId,
                    UUID actorId);

    Optional<CalendarReminderOccurrenceEntity>
            findFirstByRuleIdAndWorkspaceIdAndCreatedByUserIdOrderBySequenceNumberDesc(
                    UUID ruleId, UUID workspaceId, UUID actorId);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    List<CalendarReminderOccurrenceEntity>
            findByStatusAndScheduledAtLessThanEqualAndWorkspaceIdAndCreatedByUserIdOrderByScheduledAt(
                    CalendarReminderOccurrenceEntity.Status status,
                    Instant dueAt,
                    UUID workspaceId,
                    UUID actorId,
                    Pageable pageable);
}
