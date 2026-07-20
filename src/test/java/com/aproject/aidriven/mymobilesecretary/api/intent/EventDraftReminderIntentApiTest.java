package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.event.domain.EventIntakeDraft;
import com.aproject.aidriven.mymobilesecretary.event.persistence.EventIntakeDraftRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Regression for intent issues #97-#101: a pending event draft must not swallow a reminder. */
class EventDraftReminderIntentApiTest extends IntegrationTestBase {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private EventIntakeDraftRepository draftRepository;

    @Autowired
    private Clock clock;

    @Test
    void explicitReminderCreatesTaskAndKeepsEventTimeUnresolved() throws Exception {
        say("JCConf 2026 門票開賣；活動日期：2026年9月11日；活動地點：臺大醫院國際會議中心")
                .andExpect(jsonPath("$.action").value("CONTEXT_UPDATED"));

        LocalDate tomorrow = LocalDate.now(clock.withZone(TAIPEI)).plusDays(1);
        var dueAt = tomorrow.atTime(LocalTime.of(15, 0)).atZone(TAIPEI);
        stub.nextCommand(new IntentCommand(IntentCommand.Type.CREATE_TASK,
                "補充 JCConf 2026 確切時間", dueAt.toOffsetDateTime().toString(),
                null, null, null, "NORMAL", null, null, null, null, null, null));

        say("明天下午三點提醒我要告訴你確切時間")
                .andExpect(jsonPath("$.action").value("TASK_CREATED"));

        var task = taskService.listOpenTasks().stream()
                .filter(item -> item.getTitle().equals("補充 JCConf 2026 確切時間"))
                .findFirst().orElseThrow();
        assertThat(task.getDueAt()).isEqualTo(dueAt.toInstant());
        assertThat(scheduleService.listSchedules(null)).isEmpty();

        EventIntakeDraft draft = draftRepository.findAll().getFirst();
        assertThat(draft.getStatus()).isEqualTo(EventIntakeDraft.Status.PENDING);
        assertThat(draft.getPayload()).contains("\"startTime\":null", "\"endTime\":null");
    }

    private org.springframework.test.web.servlet.ResultActions say(String text) throws Exception {
        return mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "%s"}
                                """.formatted(text)))
                .andExpect(status().isOk());
    }
}
