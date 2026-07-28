package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Revalidates a schedule resource through its domain service under the current workspace context. */
@Component
public final class ScheduleConversationFocusContributor implements ConversationFocusContributor {

    private final ScheduleService schedules;

    public ScheduleConversationFocusContributor(ScheduleService schedules) {
        this.schedules = Objects.requireNonNull(schedules, "schedules");
    }

    @Override
    public String rootDomain() {
        return "SCHEDULE";
    }

    @Override
    public boolean isAvailable(ConversationFocusTargetResolver.ResourceTarget target) {
        Long scheduleId = TaskConversationFocusContributor.resourceId(target, "schedule:");
        if (scheduleId == null) {
            return false;
        }
        try {
            ScheduleItem schedule = schedules.getSchedule(scheduleId);
            return schedule.getTitle().equals(target.safeLabel());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
