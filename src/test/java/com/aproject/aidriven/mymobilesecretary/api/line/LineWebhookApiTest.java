package com.aproject.aidriven.mymobilesecretary.api.line;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * LINE webhook 整合測試:驗簽 → 解析 → 走真實 IntentService(stub 解析器)→ 建任務。
 *
 * channel secret 固定為 application-test.yaml 的 "test-channel-secret"。
 */
class LineWebhookApiTest extends IntegrationTestBase {

    private static final String TEST_SECRET = "test-channel-secret";

    @Autowired
    private StubIntentInterpreter stub;

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private byte[] textMessageEvent(String text) {
        return """
                {"events":[{"type":"message","replyToken":"rt-1","message":{"type":"text","text":"%s"}}]}
                """.formatted(text).getBytes(StandardCharsets.UTF_8);
    }

    /** 驗簽失敗 → 401,且不得處理內容(不建任務)。 */
    @Test
    void invalidSignatureReturns401AndDoesNothing() throws Exception {
        byte[] body = textMessageEvent("LINE webhook 驗簽失敗測試");

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", "bogus-signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    /** 缺簽章標頭 → 401。 */
    @Test
    void missingSignatureHeaderReturns401() throws Exception {
        mockMvc.perform(post("/api/line/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(textMessageEvent("no signature")))
                .andExpect(status().isUnauthorized());
    }

    /** 正確簽章 + 文字訊息 → 200,走過 IntentService 建立任務。 */
    @Test
    void validSignatureProcessesTextMessage() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "LINE webhook 測試任務", null, null, null, null, "NORMAL", null));
        byte[] body = textMessageEvent("幫我記一下");

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** 非文字事件(如 follow)一樣回 200,只是不觸發意圖處理。 */
    @Test
    void nonTextEventIsIgnoredButReturns200() throws Exception {
        byte[] body = """
                {"events":[{"type":"follow","replyToken":"rt-2"}]}
                """.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    /** 空事件陣列(LINE 平台的 webhook 驗證請求)→ 200。 */
    @Test
    void emptyEventsReturns200() throws Exception {
        byte[] body = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}
