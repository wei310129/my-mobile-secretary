package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.intent.application.ActivityCountAnswerService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationIntentHandler;
import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationSnapshot;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.LastActivityAnswerService;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConversationIntentHandlerTest {

    private ConversationIntentHandler handler;
    private MediaStorageService mediaStorageService;

    @BeforeEach
    void setUp() {
        ConversationContextService contextService = mock(ConversationContextService.class);
        mediaStorageService = mock(MediaStorageService.class);
        when(contextService.snapshot()).thenReturn(ConversationSnapshot.empty());
        handler = new ConversationIntentHandler(
                contextService,
                mock(ActivityCountAnswerService.class),
                mock(LastActivityAnswerService.class),
                mediaStorageService);
    }

    @Test
    void phaseARegistersEveryMigratedConversationType() {
        assertThat(handler.supportedTypes()).containsExactlyInAnyOrderElementsOf(Set.of(
                IntentCommand.Type.EXPLAIN_LAST_FAILURE,
                IntentCommand.Type.ASK_ACTIVITY_COUNT,
                IntentCommand.Type.ASK_LAST_ACTIVITY,
                IntentCommand.Type.ASK_STORED_MEDIA,
                IntentCommand.Type.SOCIAL));
    }

    @Test
    void socialUsesModelReplyWhenPresentAndKeepsExistingDefault() {
        IntentResult supplied = handler.handle("謝謝", command(IntentCommand.Type.SOCIAL, "不用客氣。"));
        IntentResult fallback = handler.handle("謝謝", command(IntentCommand.Type.SOCIAL, null));

        assertThat(supplied.action()).isEqualTo(IntentResult.Action.SOCIAL_REPLIED);
        assertThat(supplied.message()).isEqualTo(
                IntentResult.message(IntentResult.Action.SOCIAL_REPLIED, "不用客氣。").message());
        assertThat(fallback.message()).isEqualTo(IntentResult.message(
                IntentResult.Action.SOCIAL_REPLIED, "不客氣,有需要再叫我。").message());
    }

    @Test
    void explainLastFailureKeepsExistingNoHistoryReply() {
        IntentResult result = handler.handle(
                "為什麼失敗", command(IntentCommand.Type.EXPLAIN_LAST_FAILURE, null));

        assertThat(result.action()).isEqualTo(IntentResult.Action.FAILURE_EXPLAINED);
        assertThat(result.message()).isEqualTo(IntentResult.message(
                IntentResult.Action.FAILURE_EXPLAINED, "目前沒有可追查的上一筆解析失敗紀錄。").message());
    }

    @Test
    void chatDeleteRequestNeverCallsDestructiveMediaService() {
        IntentResult result = handler.handle(
                "把剛才的照片刪掉", command(IntentCommand.Type.ASK_STORED_MEDIA, null));

        assertThat(result.action()).isEqualTo(IntentResult.Action.MEDIA_FILES_LISTED);
        assertThat(result.message()).contains("聊天視窗不支援刪除", "App 的檔案管理", "沒有異動");
        org.mockito.Mockito.verifyNoInteractions(mediaStorageService);
    }

    private static IntentCommand command(IntentCommand.Type type, String reason) {
        return new IntentCommand(type, null, null, null, null, null, null, reason,
                null, null, null, null, null);
    }
}
