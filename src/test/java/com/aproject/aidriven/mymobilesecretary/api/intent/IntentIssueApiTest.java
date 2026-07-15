package com.aproject.aidriven.mymobilesecretary.api.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.domain.IntentIssue;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.IntentIssueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 意圖問題閉環測試:聽不懂的話自動入庫 → 開發者查 OPEN 清單 → 標記處理。
 */
class IntentIssueApiTest extends IntegrationTestBase {

    @Autowired
    private StubIntentInterpreter stub;
    @Autowired
    private IntentIssueRepository issueRepository;

    private void say(String text) throws Exception {
        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"%s\"}".formatted(text)))
                .andExpect(status().isOk());
    }

    /** 聽不懂(UNKNOWN)→ 自動記一筆 OPEN 的 CLARIFICATION 問題。 */
    @Test
    void unknownIntentIsRecordedAsOpenIssue() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                "無法判斷是查待辦還是查行程", null, null, null));
        say("問題紀錄測試-待會有什麼可以順便做");

        var issue = issueRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(i -> i.getUtterance().contains("問題紀錄測試"))
                .findFirst().orElseThrow();
        assertThat(issue.getStatus()).isEqualTo(IntentIssue.Status.OPEN);
        assertThat(issue.getCategory()).isEqualTo(IntentIssue.Category.CLARIFICATION);
        assertThat(issue.getBotReply()).contains("無法判斷");
    }

    /** LLM 失敗退回保底 → 記 FALLBACK。 */
    @Test
    void interpreterFailureIsRecordedAsFallbackIssue() throws Exception {
        // stub 沒塞回覆 = 模擬 LLM 炸掉
        say("問題紀錄測試-保底路徑");

        var issue = issueRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(i -> i.getUtterance().contains("保底路徑"))
                .findFirst().orElseThrow();
        assertThat(issue.getCategory()).isEqualTo(IntentIssue.Category.FALLBACK);
    }

    /** 開發工作流:查 OPEN → 標 RESOLVED → 不再出現在 OPEN 清單;重複標記 → 422。 */
    @Test
    void resolveWorkflowTransitionsAndRejectsDoubleResolve() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                "聽不懂", null, null, null));
        say("問題紀錄測試-解決流程");
        Long id = issueRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(i -> i.getUtterance().contains("解決流程"))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(patch("/api/intent-issues/{id}/resolve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        mockMvc.perform(patch("/api/intent-issues/{id}/resolve", id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    /** 範圍外請求(如要求寫程式)標 OUT_OF_SCOPE,不處理。 */
    @Test
    void outOfScopeMarking() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.UNKNOWN, null, null, null, null, null, null,
                "這不是排程相關請求", null, null, null));
        say("問題紀錄測試-幫我寫python");
        Long id = issueRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(i -> i.getUtterance().contains("寫python"))
                .findFirst().orElseThrow().getId();

        mockMvc.perform(patch("/api/intent-issues/{id}/out-of-scope", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OUT_OF_SCOPE"));
    }

    @Test
    void unknownIssueIdReturns404() throws Exception {
        mockMvc.perform(patch("/api/intent-issues/{id}/resolve", 999999))
                .andExpect(status().isNotFound());
    }
}
