package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TaskMutationIntentHandlerRecurrenceTest {

    @Test
    void weekdayRecurrenceMustNotSilentlyDegradeToOneTime() {
        Task.Recurrence recurrence = ReflectionTestUtils.invokeMethod(
                TaskMutationIntentHandler.class, "parseRecurrence", "WEEKDAYS");

        assertThat(recurrence).isNotNull();
        assertThat(recurrence.name()).isEqualTo("WEEKDAYS");
    }
}
