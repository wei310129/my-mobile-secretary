package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.ReminderPreference;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.ReminderPreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReminderPreferenceServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-01T15:30:00Z"); // 台北 23:30

    @Mock
    private ReminderPreferenceRepository repository;

    private ReminderPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new ReminderPreferenceService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void overnightQuietHoursDeferUntilNextMorning() {
        ReminderPreference preference = preference(true);
        when(repository.findById(1)).thenReturn(Optional.of(preference));

        Optional<Instant> deferred = service.deferUntil(task(TaskPriority.NORMAL), NOW);

        assertThat(deferred).contains(Instant.parse("2030-08-01T23:00:00Z")); // 台北隔日 07:00
    }

    @Test
    void highPriorityCanBypassQuietHoursWhenAllowed() {
        ReminderPreference preference = preference(true);
        when(repository.findById(1)).thenReturn(Optional.of(preference));

        assertThat(service.deferUntil(task(TaskPriority.HIGH), NOW)).isEmpty();
    }

    @Test
    void highPriorityIsDeferredWhenExceptionDisabled() {
        ReminderPreference preference = preference(false);
        when(repository.findById(1)).thenReturn(Optional.of(preference));

        assertThat(service.deferUntil(task(TaskPriority.HIGH), NOW))
                .contains(Instant.parse("2030-08-01T23:00:00Z"));
    }

    @Test
    void temporaryMuteEndingInsideQuietHoursExtendsToQuietEnd() {
        ReminderPreference preference = preference(false);
        preference.muteUntil(Instant.parse("2030-08-01T20:00:00Z"), NOW); // 台北 04:00
        when(repository.findById(1)).thenReturn(Optional.of(preference));

        assertThat(service.deferUntil(task(TaskPriority.NORMAL), NOW))
                .contains(Instant.parse("2030-08-01T23:00:00Z"));
    }

    private static ReminderPreference preference(boolean allowHigh) {
        ReminderPreference preference = ReminderPreference.create(NOW);
        preference.setQuietHours(LocalTime.of(23, 0), LocalTime.of(7, 0), allowHigh, NOW);
        return preference;
    }

    private static Task task(TaskPriority priority) {
        return Task.create("提醒", null, priority, NOW, NOW);
    }
}
