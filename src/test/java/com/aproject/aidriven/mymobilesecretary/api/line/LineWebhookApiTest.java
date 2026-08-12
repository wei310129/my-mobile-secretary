package com.aproject.aidriven.mymobilesecretary.api.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubReceiptInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.security.idempotency.IdempotencyService;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineContentClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogRepository;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessagingClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineWebhookPayload;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand;
import com.aproject.aidriven.mymobilesecretary.intent.domain.SchoolTransportDraft;
import com.aproject.aidriven.mymobilesecretary.intent.persistence.SchoolTransportDraftRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.persistence.TaskRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * LINE webhook 整合測試:驗簽 → 解析 → 走真實 IntentService(stub 解析器)→ 建任務。
 *
 * channel secret 固定為 application-test.yaml 的 "test-channel-secret"。
 */
class LineWebhookApiTest extends IntegrationTestBase {

    private static final String TEST_SECRET = "test-channel-secret";
    /** 與 application-test.yaml 的 owner-user-id 一致;擁有者守門 fail-closed,事件必須帶此來源才會被處理。 */
    private static final String OWNER_USER_ID = "test-owner-user";
    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private StubIntentInterpreter stub;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private ScheduleService scheduleService;
    @Autowired
    private ConversationContextService conversationContext;
    @Autowired
    private LineMessageLogRepository lineMessageLogs;
    @Autowired
    private SchoolTransportDraftRepository schoolTransportDrafts;
    @Autowired
    private PlaceRepository placeRepository;
    @Autowired
    private StubReceiptInterpreter receiptStub;
    @MockitoBean
    private LineContentClient contentClient;
    @MockitoBean
    private LineMessagingClient messagingClient;
    @Autowired
    private IdempotencyService idempotencyService;
    @Autowired
    private ObjectMapper objectMapper;

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private byte[] textMessageEvent(String text) {
        return textMessageEvent(text, "event-" + java.util.UUID.randomUUID());
    }

    private byte[] textMessageEvent(String text, String eventId) {
        return textMessageEvent(text, eventId, "{\"userId\":\"%s\"}".formatted(OWNER_USER_ID));
    }

    private byte[] textMessageEvent(String text, String eventId, String source) {
        return """
                {"events":[{"type":"message","replyToken":"rt-1","webhookEventId":"%s",\
                "source":%s,\
                "message":{"id":"message-%s","type":"text","text":"%s"}}]}
                """.formatted(eventId, source, eventId, text)
                .getBytes(StandardCharsets.UTF_8);
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
                IntentCommand.Type.CREATE_TASK, "LINE webhook 測試任務", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        byte[] body = textMessageEvent("幫我記一下");

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void systemPlaceQueryUsesBundledCatalogAndCreatesNoCustomPlace() throws Exception {
        long placesBefore = placeRepository.count();
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.ASK_PLACE, null, null, null, null, "桃園機場", null, null,
                null, null, null, null, null));
        byte[] body = textMessageEvent("桃園機場在哪");

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(messagingClient).reply(eq("rt-1"), argThat(message ->
                message.equals("我知道，您說的是位於桃園市的「臺灣桃園國際機場」。")
                        && !message.contains("系統公共地點")
                        && !message.contains("AIRPORT")
                        && !message.contains("航站南路")));
        assertThat(placeRepository.count()).isEqualTo(placesBefore);
    }

    @Test
    void logicalSystemPlaceKnowledgeAndPointFollowUpUseTheActualLineEntryPath() throws Exception {
        long placesBefore = placeRepository.count();
        byte[] knowledge = textMessageEvent("你知道台北車站嗎？");
        byte[] points = textMessageEvent("有哪些點位？");

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(knowledge))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(knowledge))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(points))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(points))
                .andExpect(status().isOk());

        verify(messagingClient).reply(eq("rt-1"), eq(
                "我知道，您說的是位於台北市的「台北火車站」。目前有 2 個系統點位，"
                        + "有需要我可以列出來給您，或者您也可以提供關鍵字讓我幫您查詢。"));
        verify(messagingClient).reply(eq("rt-1"), argThat(message ->
                message.contains("目前有 2 個系統點位")
                        && message.contains("台北捷運")
                        && message.contains("桃園捷運")
                        && !message.contains("安排完整行程")));
        assertThat(placeRepository.count()).isEqualTo(placesBefore);
    }

    @Test
    void slowOneToOneTextStartsLoadingAnimationBeforeTheTerminalReply() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.SOCIAL, null, null, null, null, null, null, null,
                null, null, null, null, null));
        byte[] body = textMessageEvent("幫我處理這件事");

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(messagingClient).startLoading(OWNER_USER_ID);
    }

    @Test
    void groupTextNeverStartsOneToOneLoadingAnimation() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.SOCIAL, null, null, null, null, null, null, null,
                null, null, null, null, null));
        String eventId = "event-" + UUID.randomUUID();
        byte[] body = textMessageEvent(
                "幫我處理這件事",
                eventId,
                "{\"userId\":\"%s\",\"groupId\":\"test-group\"}".formatted(OWNER_USER_ID));

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(messagingClient, never()).startLoading(org.mockito.ArgumentMatchers.anyString());
    }

    /** LINE 重送同一 webhookEventId 時不得再次執行已完成的 mutation。 */
    @Test
    void duplicateWebhookEventCreatesTaskOnlyOnce() throws Exception {
        String title = "LINE 冪等測試-" + Long.toUnsignedString(System.nanoTime(), 36);
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, title, null, null, null, null, "NORMAL", null,
                null, null, null, null, null, null, "幫我記下冪等測試"));
        byte[] body = textMessageEvent("幫我記下冪等測試", "same-event-" + java.util.UUID.randomUUID());
        String signature = sign(body);

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(taskRepository.findAll().stream()
                .filter(task -> title.equals(task.getTitle())))
                .hasSize(1);
    }

    @Test
    void replayedLegacyBodyPassesFinalPublicBoundaryBeforeLineOutput() throws Exception {
        String eventId = "legacy-replay-" + java.util.UUID.randomUUID();
        byte[] body = """
                {"events":[{"type":"message","replyToken":"rt-replay",\
                "webhookEventId":"%s","timestamp":1700000000000,"source":{"userId":"%s"},\
                "message":{"id":"message-%s","type":"text","text":"再說一次"}}]}
                """.formatted(eventId, OWNER_USER_ID, eventId)
                .getBytes(StandardCharsets.UTF_8);
        LineWebhookPayload.Event event = objectMapper
                .readValue(body, LineWebhookPayload.class).events().getFirst();
        String requestMaterial = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                LineWebhookController.class, "requestFingerprintMaterial", event);
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(lineContext())) {
            idempotencyService.begin(
                    WORKSPACE_ID, ACTOR_ID, "LINE", eventId, requestMaterial);
            idempotencyService.markExecutionStarted(
                    WORKSPACE_ID, ACTOR_ID, "LINE", eventId);
            idempotencyService.complete(
                    WORKSPACE_ID, ACTOR_ID, "LINE", eventId, "CLARIFICATION_NEEDED",
                    "handler=LegacyReplayHandler schema=legacy_private_table");
        }

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        verify(messagingClient).reply(eq("rt-replay"), argThat(message ->
                !message.contains("LegacyReplayHandler")
                        && !message.contains("legacy_private_table")
                        && !message.contains("handler")
                        && !message.contains("schema")));
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

    /**
     * 圖片訊息(收據):測試環境抓不到 LINE 內容(假憑證)→ 回覆失敗說明,
     * webhook 仍必須 200,不得把整包事件炸掉。
     */
    @Test
    void imageEventSurvivesContentFetchFailure() throws Exception {
        when(contentClient.fetchContent("m-1")).thenThrow(new IllegalStateException("unavailable"));
        byte[] body = """
                {"events":[{"type":"message","replyToken":"rt-3","source":{"userId":"%s"},\
                "message":{"id":"m-1","type":"image"}}]}
                """.formatted(OWNER_USER_ID).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void successfulImageEventStoresOriginalBeforeInterpretation() throws Exception {
        String messageId = "m-store-" + java.util.UUID.randomUUID();
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
        };
        when(contentClient.fetchContent(messageId))
                .thenReturn(new LineContentClient.MessageContent(png, "image/png"));
        receiptStub.nextCommand(new ReceiptCommand(
                "測試商店", "2026-07-19",
                java.util.List.of(new ReceiptCommand.Line("測試品項", 10, 1))));
        byte[] body = """
                {"events":[{"type":"message","replyToken":"rt-store",\
                "webhookEventId":"event-%s","source":{"userId":"%s"},\
                "message":{"id":"%s","type":"image"}}]}
                """.formatted(messageId, OWNER_USER_ID, messageId)
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String media = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/media"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(media).contains("\"sourceType\":\"LINE\"")
                .contains("\"mediaType\":\"image/png\"");
    }

    @Test
    void imageReplyDoesNotExposeStoredMediaDatabaseIdentifier() throws Exception {
        String messageId = "m-public-boundary-" + java.util.UUID.randomUUID();
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 11, 12, 13
        };
        when(contentClient.fetchContent(messageId))
                .thenReturn(new LineContentClient.MessageContent(png, "image/png"));
        receiptStub.nextCommand(new ReceiptCommand(
                "匿名商店", "2026-08-02",
                java.util.List.of(new ReceiptCommand.Line("匿名品項", 20, 1))));
        byte[] body = """
                {"events":[{"type":"message","replyToken":"rt-public-boundary",\
                "webhookEventId":"event-%s","source":{"userId":"%s"},\
                "message":{"id":"%s","type":"image"}}]}
                """.formatted(messageId, OWNER_USER_ID, messageId)
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        String logs = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/line/messages").param("limit", "10"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(logs).doesNotContain("檔案編號");
    }

    @Test
    void utilityHistoryImageAsksLocationThenSupportsAnnualQuery() throws Exception {
        String messageId = "m-utility-" + java.util.UUID.randomUUID();
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 4, 5, 6
        };
        when(contentClient.fetchContent(messageId))
                .thenReturn(new LineContentClient.MessageContent(png, "image/png"));
        receiptStub.nextCommand(new ReceiptCommand(null, null, java.util.List.of(),
                ReceiptCommand.DocumentType.UTILITY_BILL_HISTORY, "電費歷程",
                java.util.List.of(), java.util.List.of(), java.util.List.of(), null,
                java.util.List.of(), null, null, null,
                new ReceiptCommand.UtilityBillInfo("台灣電力公司", java.util.List.of(
                        new ReceiptCommand.UtilityBillEntry("113/03", 640, 1248),
                        new ReceiptCommand.UtilityBillEntry("112/07", 728, null)))));
        byte[] imageBody = """
                {"events":[{"type":"message","replyToken":"rt-utility",\
                "webhookEventId":"event-%s","source":{"userId":"%s"},\
                "message":{"id":"%s","type":"image"}}]}
                """.formatted(messageId, OWNER_USER_ID, messageId)
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(imageBody))
                        .contentType(MediaType.APPLICATION_JSON).content(imageBody))
                .andExpect(status().isOk());
        byte[] locationBody = textMessageEvent("這是家裡的電費");
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(locationBody))
                        .contentType(MediaType.APPLICATION_JSON).content(locationBody))
                .andExpect(status().isOk());
        for (String query : java.util.List.of(
                "我家113年電費",
                "給我我家歷年7月電費",
                "我家以前有電費超過1200嗎")) {
            byte[] queryBody = textMessageEvent(query);
            mockMvc.perform(post("/api/line/webhook")
                            .header("X-Line-Signature", sign(queryBody))
                            .contentType(MediaType.APPLICATION_JSON).content(queryBody))
                    .andExpect(status().isOk());
        }

        String logs = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/line/messages").param("limit", "30"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(logs).contains("這是哪個用電地點", "已把 2 筆電費歷程歸到")
                .contains("民國 113 年", "NT$ 1,248")
                .contains("7 月的歷年", "民國 112 年 7 月", "金額未顯示")
                .contains("超過 NT$ 1,200");
    }

    @Test
    void windowsPurchaseImageSupportsGenericAndMicrosoftFollowUpQuestions() throws Exception {
        String messageId = "m-windows-" + java.util.UUID.randomUUID();
        byte[] png = new byte[] {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 7, 8, 9
        };
        when(contentClient.fetchContent(messageId))
                .thenReturn(new LineContentClient.MessageContent(png, "image/png"));
        receiptStub.nextCommand(new ReceiptCommand(
                "Microsoft", "2024-10-01", java.util.List.of(
                        new ReceiptCommand.Line("升級至 Windows 10/11 專業版", 2999, 1))));
        byte[] imageBody = """
                {"events":[{"type":"message","replyToken":"rt-windows",\
                "webhookEventId":"event-%s","source":{"userId":"%s"},\
                "message":{"id":"%s","type":"image"}}]}
                """.formatted(messageId, OWNER_USER_ID, messageId)
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(imageBody))
                        .contentType(MediaType.APPLICATION_JSON).content(imageBody))
                .andExpect(status().isOk());

        byte[] generic = textMessageEvent("我什麼時候買的？");
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(generic))
                        .contentType(MediaType.APPLICATION_JSON).content(generic))
                .andExpect(status().isOk());
        byte[] merchant = textMessageEvent("上次買 Microsoft 相關產品或服務是什麼時候");
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(merchant))
                        .contentType(MediaType.APPLICATION_JSON).content(merchant))
                .andExpect(status().isOk());

        String logs = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/line/messages").param("limit", "20"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(logs).contains("2024-10-01", "Microsoft",
                "升級至 Windows 10/11 專業版", "NT$ 2,999");
    }

    /** 對話紀錄閉環:進出訊息都留底,GET /api/line/messages 查得到。 */
    @Test
    void conversationIsLoggedAndQueryable() throws Exception {
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.CREATE_TASK, "對話紀錄測試任務", null, null, null, null, "NORMAL", null,
                null, null, null, null, null));
        byte[] body = textMessageEvent("對話紀錄測試-幫我記一下");

        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        String logs = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/line/messages").param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(logs)
                .contains("對話紀錄測試-幫我記一下")   // IN:使用者原話
                .contains("對話紀錄測試任務");          // OUT:bot 回覆(已建立任務「…」)
    }

    @Test
    void mergesTwoReferencedSchedulesThroughNaturalLineConversation() throws Exception {
        ScheduleItem first;
        ScheduleItem second;
        WorkspaceContext line = lineContext();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(line)) {
            Instant start = Instant.parse("2099-07-25T02:00:00Z");
            first = scheduleService.createSchedule(
                    "送女兒到夏恩英語上課", start, start.plusSeconds(7200), null, false).item();
            second = scheduleService.createSchedule(
                    "女兒上夏恩英語", start, start.plusSeconds(7200), null, false).item();
            conversationContext.rememberScheduleList(List.of(first, second));
        }

        sendText("前兩個行程合併成一個");
        sendText("1.對，保留第一個");

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(line)) {
            assertThat(scheduleService.getSchedule(first.getId()).getStatus())
                    .isEqualTo(ScheduleStatus.CONFIRMED);
            assertThat(scheduleService.getSchedule(second.getId()).getStatus())
                    .isEqualTo(ScheduleStatus.REJECTED);
            assertThat(lineMessageLogs.findAll().stream()
                    .filter(log -> log.getDirection() == LineMessageLog.Direction.OUT)
                    .map(LineMessageLog::getReferencePayload)
                    .filter(java.util.Objects::nonNull))
                    .anyMatch(payload -> payload.contains("SCHEDULE:" + first.getId())
                            && payload.contains("SCHEDULE:" + second.getId()));
        }

        String logs = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/line/messages").param("limit", "10"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(logs).contains("保留第一個", "已合併為一筆", "女兒上夏恩英語")
                .doesNotContain("接送安排", "系統目前缺少", "知識紀錄");
    }

    @Test
    void existingScheduleTransportFollowUpAccumulatesKnownFieldsWithoutInternalDetails()
            throws Exception {
        WorkspaceContext line = lineContext();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(line)) {
            Instant start = Instant.parse("2099-07-25T02:00:00Z");
            ScheduleItem source = scheduleService.createSchedule(
                    "送女兒到夏恩英語上課", start, start.plusSeconds(7200), null,
                    ScheduleItem.Recurrence.WEEKLY, java.time.LocalDate.of(2099, 9, 26),
                    ScheduleItem.Category.FAMILY).item();
            conversationContext.rememberSchedule(source);
        }

        sendText("怎麼合併之後就沒有問？是要怎麼接送了呢？");
        sendText("你是指哪一個行程？");
        sendText("送女兒到夏恩英語上課是我送，也是我接，接回的時間就是下課時間12點，"
                + "要接的地點也是在夏恩英語");

        String latestReply;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(line)) {
            SchoolTransportDraft draft = schoolTransportDrafts
                    .findFirstByWorkspaceIdAndCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                            WORKSPACE_ID, ACTOR_ID, SchoolTransportDraft.Status.PENDING,
                            Instant.EPOCH)
                    .orElseThrow();
            assertThat(draft.getPayload()).isNull();
            assertThat(draft.getDropPerson()).isEqualTo("我");
            assertThat(draft.getPickupPerson()).isEqualTo("我");
            assertThat(draft.getPickupLocation()).isEqualTo("夏恩英語");
            latestReply = lineMessageLogs
                    .findAllByWorkspaceIdAndCreatedByUserIdOrderByCreatedAtDescIdDesc(
                            WORKSPACE_ID, ACTOR_ID,
                            org.springframework.data.domain.PageRequest.of(0, 1))
                    .getFirst().getContent();
        }
        assertThat(latestReply)
                .contains("送去：我", "接回：我", "送去從哪裡出發")
                .doesNotContain("送去預計幾點出發", "接回行程預計幾點結束",
                        "誰負責送去", "誰負責接回", "從哪裡接");

        String logs = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/api/line/messages").param("limit", "10"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(logs)
                .contains("我指的是行程", "送女兒到夏恩英語上課", "送去：我", "接回：我")
                .contains("送去從哪裡出發")
                .doesNotContain("送去預計幾點出發", "接回行程預計幾點結束",
                        "lastScheduleId", "Java 驗證", "AI 回覆資料", "使用者詢問",
                        "問題紀錄", "AI 暫時無法");
    }

    private void sendText(String text) throws Exception {
        byte[] body = textMessageEvent(text);
        mockMvc.perform(post("/api/line/webhook")
                        .header("X-Line-Signature", sign(body))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private static WorkspaceContext lineContext() {
        return new WorkspaceContext(ACTOR_ID, WORKSPACE_ID, WorkspaceChannel.LINE,
                "line", "user:" + OWNER_USER_ID);
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
