package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderRuleView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared start-time reminder lifecycle for Calendar conversation capabilities. */
@Service
@Transactional
public class CalendarStartReminderLifecycleService {

    private final CalendarReminderApplicationService reminders;

    public CalendarStartReminderLifecycleService(
            CalendarReminderApplicationService reminders) {
        this.reminders = reminders;
    }

    @Transactional(readOnly = true)
    public Decision afterStartTimeCreated(
            UUID planId, String nodeKey, PublicKind kind) {
        return decision(planId, nodeKey, kind, false);
    }

    @Transactional(readOnly = true)
    public Decision beforeStartTimeAdjusted(
            UUID planId, String nodeKey, PublicKind kind) {
        return decision(planId, nodeKey, kind, true);
    }

    public int createAdaptiveDepartureReminder(
            UUID planId, String nodeKey, Duration routeDuration) {
        return reminders.createAdaptiveDepartureReminders(
                        planId,
                        nodeKey,
                        routeDuration,
                        CalendarReminderDeliveryMode.ONCE,
                        null)
                .size();
    }

    public List<CalendarReminderRuleView> createAdaptiveDepartureReminderSchedule(
            UUID planId, String nodeKey, Duration routeDuration) {
        return reminders.createAdaptiveDepartureReminders(
                planId,
                nodeKey,
                routeDuration,
                CalendarReminderDeliveryMode.ONCE,
                null);
    }

    public CalendarReminderRuleView createExplicitDepartureReminder(
            UUID planId, String nodeKey, int leadMinutes) {
        if (leadMinutes < 1 || leadMinutes > 240) {
            throw new IllegalArgumentException("leadMinutes must be between 1 and 240");
        }
        return reminders.createPersonalRelativeForPlanNode(
                planId, nodeKey, Duration.ofMinutes(-leadMinutes));
    }

    @Transactional(readOnly = true)
    public List<CalendarReminderRuleView> activeDepartureReminders(
            UUID planId, String nodeKey) {
        return reminders.activePersonalStartReminders(planId, nodeKey);
    }

    public void createEarlyRideHailReminder(UUID planId, Instant fireAt) {
        reminders.createPersonalAbsoluteForPlanNode(planId, "start", fireAt);
    }

    public int clearReviewedStartReminders(UUID planId, String nodeKey) {
        return reminders.cancelPersonalStartRemindersRequiringReview(planId, nodeKey);
    }

    private Decision decision(
            UUID planId, String nodeKey, PublicKind kind, boolean adjustment) {
        var state = reminders.personalStartReminderState(planId, nodeKey);
        return switch (state) {
            case NONE -> new Decision(
                    Action.ASK,
                    adjustment
                            ? "要設定" + kind.label() + "提醒嗎？"
                            : "要設定" + kind.label() + "提醒嗎？");
            case RELATIVE_ACTIVE -> new Decision(
                    Action.RETAINED_RELATIVE,
                    "原本的" + kind.label() + "提醒規則會配合新時間主動提醒您。");
            case ABSOLUTE_ACTIVE -> new Decision(
                    Action.REVIEW_FIXED,
                    "原本的固定時間提醒需要重新確認。要改成配合新的開始時間提醒嗎？");
        };
    }

    public enum PublicKind {
        DEPARTURE("出發"),
        PREPARATION("行前");

        private final String label;

        PublicKind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    public enum Action {
        ASK,
        RETAINED_RELATIVE,
        REVIEW_FIXED
    }

    public record Decision(Action action, String message) {}
}
