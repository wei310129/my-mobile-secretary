package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.SecretTextCipher;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentDecisionTrace;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentDecisionTraceRepository;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/** End-to-end verification of request correlation, encrypted raw exchange and validation codes. */
@TestPropertySource(properties =
        "app.intent.trace.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
class IntentDecisionTraceFlowTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;
    @Autowired
    private IntentDecisionTraceRepository repository;
    @Autowired
    private SecretTextCipher cipher;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void invalidAiCommandStillRecordsEncryptedExchangeAndStableValidationCode() throws Exception {
        UUID requestId = UUID.randomUUID();
        String input = "trace validation request";
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE, "trace schedule", null,
                null, "2026-07-16T22:15:00+08:00", null, "NORMAL", null,
                null, null, null, null, false));

        var response = mockMvc.perform(post("/api/intent")
                        .header(RequestCorrelationFilter.HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("text", input))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("AI_UNAVAILABLE"))
                .andReturn().getResponse();
        String reply = objectMapper.readTree(response.getContentAsByteArray())
                .path("message").asText();

        IntentDecisionTrace trace = repository.findByRequestId(requestId).orElseThrow();
        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo(requestId.toString());
        assertThat(trace.getChannel()).isEqualTo("REST");
        assertThat(trace.getWorkspaceId()).isEqualTo(LegacyAccountIds.WORKSPACE_ID);
        assertThat(trace.getActorId()).isEqualTo(LegacyAccountIds.USER_ID);
        assertThat(trace.getSelectedCapability()).isEqualTo("CREATE_SCHEDULE");
        assertThat(trace.getValidationOutcome())
                .isEqualTo(IntentDecisionTrace.ValidationOutcome.REJECTED);
        assertThat(trace.getValidationCode()).isEqualTo("MISSING_SCHEDULE_START_AT");
        assertThat(trace.getExecutionOutcome())
                .isEqualTo(IntentDecisionTrace.ExecutionOutcome.FALLBACK);
        assertThat(trace.getStageLatenciesMs()).containsKey("total");
        assertThat(trace.getRedactedSummary())
                .contains("MISSING_SCHEDULE_START_AT")
                .doesNotContain(input, "trace schedule");
        assertThat(cipher.decrypt(new SecretTextCipher.EncryptedText(
                trace.getRawCipherKeyId(), trace.getRawInputEncrypted()))).contains(input);
        assertThat(cipher.decrypt(new SecretTextCipher.EncryptedText(
                trace.getRawCipherKeyId(), trace.getRawOutputEncrypted()))).contains(reply);

        repository.delete(trace);
    }
}
