package com.aproject.aidriven.mymobilesecretary.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.geo.application.LocationEventService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEventType;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.BufferRuleService;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.FollowUpStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.OutcomeReason;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleFollowUp;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleOutcome;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleFollowUpRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleOutcomeRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 行程結果追蹤的完整閉環測試(真實 DB + Redis):
 * 結束的行程被排定詢問 → 到期發問 → 自然語言回報 → outcome 落地、行程完成、詢問關閉;
 * 以及 GPS 離開行程地點觸發的提前詢問。
 */
class ScheduleFollowUpFlowTest extends IntegrationTestBase {

    @Autowired
    private ScheduleItemRepository scheduleItemRepository;
    @Autowired
    private ScheduleFollowUpRepository followUpRepository;
    @Autowired
    private ScheduleOutcomeRepository outcomeRepository;
    @Autowired
    private ScheduleFollowUpService followUpService;
    @Autowired
    private LocationEventService locationEventService;
    @Autowired
    private PlaceRepository placeRepository;
    @Autowired
    private StubIntentInterpreter stub;
    @Autowired
    private BufferRuleService bufferRuleService;

    /** 秒級截斷避免 DB 時間精度(微秒)造成比對誤差。 */
    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private ScheduleItem saveConfirmed(String title, Instant startAt, Instant endAt, Long placeId) {
        ScheduleItem item = ScheduleItem.propose(title, startAt, endAt, placeId, startAt);
        item.confirm(startAt);
        return scheduleItemRepository.save(item);
    }

    /** 時間路徑閉環:排定(endAt+15m)→ 發問 → LINE 式自然語言回報超時 → 全部落地。 */
    @Test
    void endedScheduleIsAskedAndOutcomeRecordedViaIntent() throws Exception {
        Instant now = now();
        ScheduleItem item = saveConfirmed("跟客戶開會", now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofMinutes(30)), null);

        followUpService.planFollowUpsForEndedSchedules();
        ScheduleFollowUp followUp = followUpRepository.findByScheduleItemId(item.getId()).orElseThrow();
        assertThat(followUp.getStatus()).isEqualTo(FollowUpStatus.SCHEDULED);
        // 結束 30 分鐘前的行程,endAt+15m 已過 → 已到期
        assertThat(followUp.getDueAt()).isEqualTo(item.getEndAt().plus(Duration.ofMinutes(15)));

        followUpService.askDueFollowUps();
        assertThat(followUpRepository.findByScheduleItemId(item.getId()).orElseThrow().getStatus())
                .isEqualTo(FollowUpStatus.ASKED);

        // 使用者(經 LINE/intent)回報:「會開晚了半小時,會議拖太久」
        stub.nextCommand(new IntentCommand(
                IntentCommand.Type.RECORD_OUTCOME, null, null, null, null, null, null, null,
                false, 30, "MEETING_OVERRUN"));
        mockMvc.perform(post("/api/intent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"會開晚了半小時,會議拖太久\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("OUTCOME_RECORDED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("超時 30 分鐘")));

        ScheduleOutcome outcome = outcomeRepository.findByScheduleItemId(item.getId()).orElseThrow();
        assertThat(outcome.isOnTime()).isFalse();
        assertThat(outcome.getOverrunMinutes()).isEqualTo(30);
        assertThat(outcome.getReason()).isEqualTo(OutcomeReason.MEETING_OVERRUN);
        // 原文留在 note,細節不丟
        assertThat(outcome.getNote()).contains("會開晚了半小時");

        // 回報 = 行程真的結束:CONFIRMED → COMPLETED;詢問關閉
        assertThat(scheduleItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(ScheduleStatus.COMPLETED);
        assertThat(followUpRepository.findByScheduleItemId(item.getId()).orElseThrow().getStatus())
                .isEqualTo(FollowUpStatus.ANSWERED);
    }

    /** GPS 路徑:EXIT 落在行程地點半徑內 → 建立 exitAt+5 分鐘的詢問(比 endAt+15m 早)。 */
    @Test
    void gpsExitFromSchedulePlacePlansEarlyFollowUp() {
        Instant now = now();
        Place place = placeRepository.save(
                Place.create("客戶辦公室", null, 24.9800, 121.5400, "辦公室", now));
        // 行程還沒到表定結束時間,人已經離開 → 實際提早結束
        ScheduleItem item = saveConfirmed("駐點支援", now.minus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(1)), place.getId());

        locationEventService.recordEvent(LocationEventType.EXIT, 24.9801, 121.5401, now, "test");

        ScheduleFollowUp followUp = followUpRepository.findByScheduleItemId(item.getId()).orElseThrow();
        assertThat(followUp.getStatus()).isEqualTo(FollowUpStatus.SCHEDULED);
        assertThat(followUp.getDueAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
    }

    /** 緩衝閉環:同地點累積 3 筆回報(30/20/準時)→ 建議緩衝 = 平均 17 分鐘。 */
    @Test
    void outcomesAccumulateIntoBufferRule() {
        Instant now = now();
        Place place = placeRepository.save(
                Place.create("常拖時間的會議室", null, 25.2000, 121.3000, "會議室", now));
        assertThat(bufferRuleService.recommendedBuffer(place.getId())).isEqualTo(java.time.Duration.ZERO);

        int[] overruns = {30, 20, 0};
        for (int overrun : overruns) {
            ScheduleItem item = saveConfirmed("週會", now.minus(Duration.ofHours(3)),
                    now.minus(Duration.ofHours(2)), place.getId());
            if (overrun > 0) {
                followUpService.recordOutcome(item.getId(), false, overrun,
                        OutcomeReason.MEETING_OVERRUN, null);
            } else {
                followUpService.recordOutcome(item.getId(), true, null, null, null);
            }
        }

        // (30 + 20 + 0) / 3 = 16.67 → 17 分鐘
        assertThat(bufferRuleService.recommendedBuffer(place.getId()))
                .isEqualTo(Duration.ofMinutes(17));
    }

    /** EXIT 離行程地點很遠(>200m)→ 不觸發詢問排定。 */
    @Test
    void gpsExitFarFromSchedulePlaceDoesNotPlanFollowUp() {
        Instant now = now();
        Place place = placeRepository.save(
                Place.create("遠方倉庫", null, 25.1000, 121.7000, "倉庫", now));
        ScheduleItem item = saveConfirmed("盤點", now.minus(Duration.ofHours(2)),
                now.plus(Duration.ofHours(1)), place.getId());

        // 約 20 公里外
        locationEventService.recordEvent(LocationEventType.EXIT, 24.9500, 121.5000, now, "test");

        assertThat(followUpRepository.findByScheduleItemId(item.getId())).isEmpty();
    }
}
