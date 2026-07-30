package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarExternalSyncConversationServiceTest {

    private final CalendarExternalSyncConversationService service =
            new CalendarExternalSyncConversationService();

    @Test
    void explainsLossyIcsBoundaryWithoutClaimingDeviceMutation() {
        List<String> utterances = List.of(
                "直接同步到我的 iPhone 行事曆",
                "幫我加進 Apple Calendar",
                "可以寫入 EventKit 嗎",
                "同步手機日曆",
                "匯出到蘋果行事曆",
                "把這個行程放到 iOS",
                "能跟 iPhone 雙向同步嗎",
                "幫我更新裝置上的行事曆",
                "用 EventKit 建活動",
                "下載 ICS 後會自動同步嗎",
                "外部日曆會變成主資料嗎",
                "可以背景刷新 Apple Calendar 嗎");

        for (String utterance : utterances) {
            IntentResult result = service.explain(command(utterance));
            assertThat(result.action())
                    .isEqualTo(
                            IntentResult.Action
                                    .CALENDAR_EXTERNAL_SYNC_EXPLAINED);
            assertThat(result.message())
                    .contains("ICS")
                    .contains("iOS")
                    .contains("尚未")
                    .doesNotContain(
                            "已同步",
                            "已寫入",
                            "UUID",
                            "EventKit mutation",
                            "calendar_ics");
            assertThat(result.task()).isNull();
            assertThat(result.decision()).isNull();
        }
    }

    private static IntentCommand command(String sourceText) {
        return new IntentCommand(
                IntentCommand.Type.EXPLAIN_CALENDAR_EXTERNAL_SYNC,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                sourceText);
    }
}
