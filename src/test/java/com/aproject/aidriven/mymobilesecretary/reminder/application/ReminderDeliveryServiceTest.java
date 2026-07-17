package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationRequest;
import com.aproject.aidriven.mymobilesecretary.planner.application.WeatherAdvisoryService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Reminder;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReminderDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-09T10:00:00Z");

    @Mock private NotificationPublisher publisher;
    @Mock private WeatherAdvisoryService weatherAdvisoryService;

    @Test
    void reminderIsPersistedToOutboxWithStableBusinessReferences() {
        when(weatherAdvisoryService.currentAdvisory()).thenReturn(java.util.Optional.empty());
        Reminder reminder = reminder();
        Task task = task();

        new ReminderDeliveryService(publisher, weatherAdvisoryService).deliver(reminder, task);

        ArgumentCaptor<NotificationRequest> captured = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(publisher).enqueue(captured.capture());
        assertThat(captured.getValue().reminderId()).isEqualTo(9L);
        assertThat(captured.getValue().taskId()).isEqualTo(7L);
        assertThat(captured.getValue().title()).isEqualTo("買排骨");
        assertThat(captured.getValue().deliveryKey()).startsWith("reminder:9:");
    }

    @Test
    void weatherAdvisoryIsIncludedBeforeOutboxPersistence() {
        when(weatherAdvisoryService.currentAdvisory()).thenReturn(
                java.util.Optional.of("降雨機率 70%,記得帶傘"));

        new ReminderDeliveryService(publisher, weatherAdvisoryService).deliver(reminder(), task());

        ArgumentCaptor<NotificationRequest> captured = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(publisher).enqueue(captured.capture());
        assertThat(captured.getValue().message()).contains("天氣提醒").contains("帶傘");
    }

    @Test
    void outboxFailureIsNotSwallowedSoTheBusinessTransactionCanRetry() {
        when(weatherAdvisoryService.currentAdvisory()).thenReturn(java.util.Optional.empty());
        when(publisher.enqueue(any())).thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> new ReminderDeliveryService(publisher, weatherAdvisoryService)
                .deliver(reminder(), task()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Reminder reminder() {
        Reminder reminder = Reminder.triggered(7L, "ENTER geofence: 全聯", NOW);
        ReflectionTestUtils.setField(reminder, "id", 9L);
        return reminder;
    }

    private static Task task() {
        Task task = Task.create("買排骨", null, TaskPriority.NORMAL, null, NOW);
        ReflectionTestUtils.setField(task, "id", 7L);
        return task;
    }
}
