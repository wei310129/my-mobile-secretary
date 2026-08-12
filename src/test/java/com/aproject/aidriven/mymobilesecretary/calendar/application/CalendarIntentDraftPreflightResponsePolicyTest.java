package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarRouteRiskResponsePolicy;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryItem;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalendarIntentDraftPreflightResponsePolicyTest {

    @Test
    void directOverlapListsEveryExistingTitleAndAsksOnlyOneQuestion() {
        var policy = new CalendarIntentDraftPreflightResponsePolicy(
                mock(CalendarRouteRiskResponsePolicy.class));
        var preflight = new CalendarIntentDraftPreflightService.PreflightResult(
                "ROUTE_RISK",
                "fingerprint",
                List.of(),
                List.of(
                        item("去捷運台北車站", "2026-08-05T01:00:00Z", "2026-08-05T01:26:00Z"),
                        item("看牙醫", "2026-08-05T01:20:00Z", "2026-08-05T02:00:00Z")));

        String response = policy.describe(preflight);

        assertThat(response)
                .contains("2 個既有行程", "🏷️ 去捷運台北車站", "🏷️ 看牙醫")
                .contains("08/05 09:00 ~ 08/05 09:26", "08/05 09:20 ~ 08/05 10:00")
                .contains(
                        "本次行程尚未建立",
                        "這個安排會和 2 個既有行程時間重疊",
                        "請選擇本次行程的處理方式，或直接回覆新的出發時間",
                        "1. 調整出發時間",
                        "2. 照原安排保留")
                .doesNotContain("要改用其他時間嗎")
                .doesNotContain("UUID", "planId", "nodeId");
        assertThat(policy.choiceQuestion(preflight).choices()).hasSize(2);
        assertThat(policy.choiceQuestion(preflight).resolveAction("1"))
                .contains(RouteCalendarChoiceCatalog.CHANGE_TIME);
        assertThat(policy.choiceQuestion(preflight).resolveAction("2"))
                .contains(RouteCalendarChoiceCatalog.KEEP_ORIGINAL);
    }

    private static CalendarQueryItem item(String title, String start, String end) {
        return new CalendarQueryItem(
                title,
                CalendarPlacement.interval(
                        Instant.parse(start), Instant.parse(end), ZoneId.of("Asia/Taipei")),
                null,
                null);
    }
}
