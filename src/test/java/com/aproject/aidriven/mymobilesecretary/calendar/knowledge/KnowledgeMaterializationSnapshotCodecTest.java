package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeMaterializationSnapshotCodecTest {

    private static final Instant NOW = Instant.parse("2026-07-25T03:00:00Z");
    private static final UUID NODE = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");

    @Test
    void allTypedCommandsRoundTripToCanonicalSnapshots() {
        List<KnowledgeMaterializationCommand> commands = List.of(
                new KnowledgeMaterializationCommand.CreateTask(
                        "買船票", null, TaskPriority.HIGH),
                new KnowledgeMaterializationCommand.CreateCalendarNode(
                        "boarding", "登船", NOW),
                new KnowledgeMaterializationCommand.SetPlanningPreference(20, 45),
                new KnowledgeMaterializationCommand.SetBufferRule(7L, 30, 2L),
                new KnowledgeMaterializationCommand.CreateTaskReminder(9L, NOW),
                new KnowledgeMaterializationCommand.CreateCalendarReminder(
                        NODE,
                        3L,
                        new KnowledgeMaterializationCommand.CalendarReminderTiming.Relative(
                                Duration.ofMinutes(-30)),
                        CalendarReminderDeliveryMode.ACK_REQUIRED,
                        Duration.ofMinutes(10),
                        4,
                        NotificationChannel.LOG),
                new KnowledgeMaterializationCommand.CreateCalendarReminder(
                        NODE,
                        3L,
                        new KnowledgeMaterializationCommand.CalendarReminderTiming.Absolute(NOW),
                        CalendarReminderDeliveryMode.ONCE,
                        null,
                        null,
                        NotificationChannel.WINDOWS_TOAST));

        for (KnowledgeMaterializationCommand command : commands) {
            String snapshot = KnowledgeMaterializationSnapshotCodec.encode(command);

            KnowledgeMaterializationCommand decoded =
                    KnowledgeMaterializationSnapshotCodec.decode(snapshot);

            assertThat(decoded).isEqualTo(command);
            assertThat(KnowledgeMaterializationSnapshotCodec.encode(decoded))
                    .isEqualTo(snapshot);
        }
    }

    @Test
    void malformedUnknownOrNonCanonicalSnapshotsFailClosed() {
        assertThatThrownBy(() -> KnowledgeMaterializationSnapshotCodec.decode(
                        "TASK|4:test6:<null>4:HIGHtrailing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("snapshot");
        assertThatThrownBy(() -> KnowledgeMaterializationSnapshotCodec.decode(
                        "UNKNOWN|1:x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeMaterializationSnapshotCodec.decode(
                        "PLANNING_PREFERENCE|2:01" + "1:1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KnowledgeMaterializationSnapshotCodec.decode(
                        "CALENDAR_NODE|999999999:x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
