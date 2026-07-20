package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.DeferredTask;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.DeferredTaskRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Scenario #156: dinner -> laptop pickup -> fuel reminder after confirmed pickup. */
class DependentTaskIntentApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @Autowired
    private TaskService taskService;

    @Autowired
    private DeferredTaskRepository deferredTaskRepository;

    @Test
    void completionDependentTaskDoesNotAppearOrNotifyEarly() throws Exception {
        stub.nextCommands(
                new IntentCommand(IntentCommand.Type.CREATE_SCHEDULE, "跟客戶吃飯", null,
                        "2027-07-19T19:00:00+08:00", "2027-07-19T21:00:00+08:00",
                        null, null, null, null, null, null, null, null),
                new IntentCommand(IntentCommand.Type.CREATE_TASK, "拿公司電腦",
                        "2027-07-19T21:00:00+08:00", null, null, null,
                        "NORMAL", null, null, null, null, null, null),
                new IntentCommand(IntentCommand.Type.CREATE_TASK, "回家前加油",
                        null, null, null, null, "NORMAL", null, null, null, null, null, null,
                        IntentOptions.empty().afterTaskCompletion("拿公司電腦", 0)));

        say("明晚七點先跟客戶吃飯，吃完大概九點再去公司拿電腦，拿完提醒我回家前加油")
                .andExpect(jsonPath("$.action").value("BATCH_EXECUTED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("只有確認")));

        assertThat(openExact("拿公司電腦")).hasSize(1);
        assertThat(openExact("回家前加油")).isEmpty();
        DeferredTask waiting = deferredTaskRepository.findAll().stream()
                .filter(item -> item.getTitle().equals("回家前加油"))
                .findFirst().orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(DeferredTask.Status.WAITING);

        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.COMPLETE_TASK, "拿公司電腦", null, null, null,
                null, null, null, null, null, null, null, null));
        say("公司電腦拿完了")
                .andExpect(jsonPath("$.action").value("TASK_COMPLETED"));

        var activated = openExact("回家前加油");
        assertThat(activated).hasSize(1);
        assertThat(activated.getFirst().getDueAt()).isNotNull();
        DeferredTask triggered = deferredTaskRepository.findById(waiting.getId()).orElseThrow();
        assertThat(triggered.getStatus()).isEqualTo(DeferredTask.Status.TRIGGERED);
        assertThat(triggered.getCreatedTaskId()).isEqualTo(activated.getFirst().getId());
    }

    private org.springframework.test.web.servlet.ResultActions say(String text) throws Exception {
        return mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text": "%s"}
                                """.formatted(text)))
                .andExpect(status().isOk());
    }

    private List<com.aproject.aidriven.mymobilesecretary.reminder.domain.Task> openExact(
            String title) {
        return taskService.listOpenTasks().stream()
                .filter(task -> task.getTitle().equals(title))
                .toList();
    }
}
