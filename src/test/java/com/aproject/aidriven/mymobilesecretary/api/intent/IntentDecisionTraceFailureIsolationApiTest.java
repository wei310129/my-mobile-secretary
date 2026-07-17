package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentDecisionTraceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** A trace backend outage must never change the intent response. */
class IntentDecisionTraceFailureIsolationApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;

    @MockitoBean
    private IntentDecisionTraceService decisionTraceService;

    @Test
    void traceFailureDoesNotAffectIntentResult() throws Exception {
        when(decisionTraceService.recordSafely(any()))
                .thenThrow(new IllegalStateException("trace store unavailable"));
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.LIST_TASKS, null, null, null, null, null, null,
                null, null, null, null, null, null));

        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"list trace isolation tasks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("TASKS_LISTED"));
    }
}
