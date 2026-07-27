package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public sealed interface KnowledgeMaterializationCommand {

    record CreateTask(
            String title, Instant deadline, TaskPriority priority)
            implements KnowledgeMaterializationCommand {
        public CreateTask {
            title = required(title, 200, "task title");
            Objects.requireNonNull(priority, "priority");
        }
    }

    record CreateCalendarNode(
            String nodeKey, String label, Instant time)
            implements KnowledgeMaterializationCommand {
        public CreateCalendarNode {
            nodeKey = required(nodeKey, 100, "node key");
            label = required(label, 200, "node label");
            Objects.requireNonNull(time, "time");
        }
    }

    record SetPlanningPreference(
            int transferMinutes, int mealMinutes)
            implements KnowledgeMaterializationCommand {
        public SetPlanningPreference {
            if (transferMinutes < 0 || transferMinutes > 1440
                    || mealMinutes < 0 || mealMinutes > 1440) {
                throw new IllegalArgumentException(
                        "planning preference minutes must be between 0 and 1440");
            }
        }
    }

    record SetBufferRule(
            long placeId, int bufferMinutes, long expectedExplicitRevision)
            implements KnowledgeMaterializationCommand {
        public SetBufferRule {
            if (placeId <= 0) {
                throw new IllegalArgumentException("place id must be positive");
            }
            if (bufferMinutes < 0 || bufferMinutes > 1440) {
                throw new IllegalArgumentException(
                        "explicit buffer minutes must be between 0 and 1440");
            }
            if (expectedExplicitRevision < 0) {
                throw new IllegalArgumentException(
                        "explicit buffer revision cannot be negative");
            }
        }
    }

    record CreateTaskReminder(long taskId, Instant remindAt)
            implements KnowledgeMaterializationCommand {
        public CreateTaskReminder {
            if (taskId <= 0) {
                throw new IllegalArgumentException("task id must be positive");
            }
            Objects.requireNonNull(remindAt, "remindAt");
        }
    }

    record CreateCalendarReminder(
            UUID nodeId,
            long expectedNodeRevision,
            CalendarReminderTiming timing,
            CalendarReminderDeliveryMode deliveryMode,
            Duration ackInterval,
            Integer maxAlerts,
            NotificationChannel preferredChannel)
            implements KnowledgeMaterializationCommand {
        public CreateCalendarReminder {
            Objects.requireNonNull(nodeId, "nodeId");
            if (expectedNodeRevision <= 0) {
                throw new IllegalArgumentException(
                        "node revision must be positive");
            }
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(deliveryMode, "deliveryMode");
            Objects.requireNonNull(preferredChannel, "preferredChannel");
            if (deliveryMode == CalendarReminderDeliveryMode.ONCE
                    && (ackInterval != null || maxAlerts != null)) {
                throw new IllegalArgumentException(
                        "one-time reminders cannot define acknowledgement retries");
            }
            if (deliveryMode == CalendarReminderDeliveryMode.ACK_REQUIRED
                    && (ackInterval == null
                            || ackInterval.isZero()
                            || ackInterval.isNegative()
                            || maxAlerts == null
                            || maxAlerts < 2
                            || maxAlerts > 20)) {
                throw new IllegalArgumentException(
                        "acknowledgement reminders require interval and 2..20 alerts");
            }
        }
    }

    sealed interface CalendarReminderTiming {

        record Relative(Duration offset) implements CalendarReminderTiming {
            public Relative {
                Objects.requireNonNull(offset, "offset");
                if (offset.isPositive()) {
                    throw new IllegalArgumentException(
                            "after-node reminders require a Task");
                }
            }
        }

        record Absolute(Instant fireAt) implements CalendarReminderTiming {
            public Absolute {
                Objects.requireNonNull(fireAt, "fireAt");
            }
        }
    }

    private static String required(String value, int max, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String safe = value.strip();
        if (safe.length() > max) {
            throw new IllegalArgumentException(name + " exceeds " + max);
        }
        return safe;
    }
}
