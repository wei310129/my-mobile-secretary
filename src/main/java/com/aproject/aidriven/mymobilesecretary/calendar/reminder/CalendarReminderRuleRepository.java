package com.aproject.aidriven.mymobilesecretary.calendar.reminder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CalendarReminderRuleRepository
        extends JpaRepository<CalendarReminderRuleEntity, UUID> {

    long countByNodeIdAndOwnerKindAndStatusAndWorkspaceIdAndCreatedByUserId(
            UUID nodeId,
            CalendarReminderOwnerKind ownerKind,
            CalendarReminderRuleEntity.Status status,
            UUID workspaceId,
            UUID actorId);

    List<CalendarReminderRuleEntity>
            findAllByNodeIdAndStatusAndWorkspaceIdAndCreatedByUserId(
                    UUID nodeId,
                    CalendarReminderRuleEntity.Status status,
                    UUID workspaceId,
                    UUID actorId);

    Optional<CalendarReminderRuleEntity>
            findByIdAndWorkspaceIdAndCreatedByUserId(
                    UUID id, UUID workspaceId, UUID actorId);

    boolean existsByNodeIdAndOwnerKindAndRuleKindAndOffsetSecondsAndStatusAndWorkspaceIdAndCreatedByUserId(
            UUID nodeId,
            CalendarReminderOwnerKind ownerKind,
            CalendarReminderRuleEntity.RuleKind ruleKind,
            Long offsetSeconds,
            CalendarReminderRuleEntity.Status status,
            UUID workspaceId,
            UUID actorId);

    boolean existsByNodeIdAndOwnerKindAndRuleKindAndAbsoluteFireAtAndStatusAndWorkspaceIdAndCreatedByUserId(
            UUID nodeId,
            CalendarReminderOwnerKind ownerKind,
            CalendarReminderRuleEntity.RuleKind ruleKind,
            java.time.Instant absoluteFireAt,
            CalendarReminderRuleEntity.Status status,
            UUID workspaceId,
            UUID actorId);
}
