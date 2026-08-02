package com.aproject.aidriven.mymobilesecretary.calendar.adoption;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarRouteRiskResponsePolicyTest {

    private static final Instant TEN = Instant.parse("2026-08-02T02:00:00Z");

    private final CalendarRouteRiskResponsePolicy policy =
            new CalendarRouteRiskResponsePolicy();

    @Test
    void noAdjacentRouteAsksOneUsefulQuestion() {
        assertThat(policy.describe(List.of()))
                .isEqualTo("目前沒有兩個可銜接的定點行程。要先補上行程地點嗎？");
    }

    @Test
    void insufficientEvidenceDoesNotClaimFeasibilityOrLeakDiagnostics() {
        String response = policy.describe(List.of(assessment(
                PersonalRouteStatus.INSUFFICIENT_EVIDENCE,
                Adjustability.LOCKED,
                Adjustability.LOCKED,
                null)));

        assertThat(response)
                .contains("還缺可靠路線資料", "不能判定", "哪種交通方式？")
                .doesNotContain("INSUFFICIENT", "status", "reason", "nodeId");
    }

    @Test
    void feasibleRoutesAnswerDirectlyWithoutInventingWork() {
        assertThat(policy.describe(List.of(assessment(
                        PersonalRouteStatus.FEASIBLE,
                        Adjustability.LOCKED,
                        Adjustability.LOCKED,
                        Duration.ofMinutes(30)))))
                .isEqualTo("目前相鄰行程都接得上。");
    }

    @Test
    void movableLaterNodeIsThePreferredAdjustment() {
        assertThat(policy.describe(List.of(assessment(
                        PersonalRouteStatus.ALTERNATIVE_AVAILABLE,
                        Adjustability.LOCKED,
                        Adjustability.WINDOWED,
                        Duration.ofMinutes(90)))))
                .contains("中間只有 60 分鐘", "需要約 90 分鐘", "先把後一段延後")
                .endsWith("要照原安排保留嗎？");
    }

    @Test
    void movableEarlierNodeIsThePreferredAdjustment() {
        assertThat(policy.describe(List.of(assessment(
                        PersonalRouteStatus.ALTERNATIVE_AVAILABLE,
                        Adjustability.WINDOWED,
                        Adjustability.LOCKED,
                        Duration.ofMinutes(90)))))
                .contains("先把前一段提早", "再改交通方式")
                .endsWith("要照原安排保留嗎？");
    }

    @Test
    void lockedRiskOffersPrimaryAndSecondaryAdjustmentWithoutCompletionClaim() {
        assertThat(policy.describe(List.of(assessment(
                        PersonalRouteStatus.IMPOSSIBLE,
                        Adjustability.LOCKED,
                        Adjustability.LOCKED,
                        Duration.ofMinutes(90)))))
                .contains("會接不上", "先調整後一段時間", "再換更快的交通方式")
                .doesNotContain("已儲存", "已完成");
    }

    private static PersonalRouteAssessment assessment(
            PersonalRouteStatus status,
            Adjustability fromAdjustability,
            Adjustability toAdjustability,
            Duration required) {
        return new PersonalRouteAssessment(
                status,
                constraint("淡水行程", TEN, fromAdjustability),
                constraint(
                        "新店行程",
                        TEN.plus(Duration.ofHours(1)),
                        toAdjustability),
                required,
                Duration.ofHours(1));
    }

    private static PersonalRouteConstraint constraint(
            String key, Instant time, Adjustability adjustability) {
        return new PersonalRouteConstraint(
                UUID.nameUUIDFromBytes(("plan-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes(("node-" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                UUID.nameUUIDFromBytes("route-owner".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                key,
                time,
                new CalendarLocation(key + "地點", 25.0, 121.5),
                adjustability,
                1);
    }
}
