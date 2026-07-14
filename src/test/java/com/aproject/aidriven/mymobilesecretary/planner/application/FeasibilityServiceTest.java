package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEventType;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.BufferRuleService;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityResult;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 可行性引擎規則測試:重疊、前後行程交通、目前位置(高雄→台北)、邊界值。
 * 25 km/h + 10 分鐘緩衝(與正式預設一致)。
 */
@ExtendWith(MockitoExtension.class)
class FeasibilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-11T02:00:00Z");
    /** 台北市中心與高雄市中心座標。 */
    private static final double TPE_LAT = 25.0330, TPE_LON = 121.5654;
    private static final double KHH_LAT = 22.6120, KHH_LON = 120.3000;

    @Mock
    private ScheduleItemRepository scheduleItemRepository;

    @Mock
    private PlaceRepository placeRepository;

    @Mock
    private LocationEventRepository locationEventRepository;

    @Mock
    private BufferRuleService bufferRuleService;

    private FeasibilityService service;

    @BeforeEach
    void setUp() {
        // 用直線估算器(確定性),TDX 版在 CompositeTravelTimeEstimatorTest 另測
        service = new FeasibilityService(
                scheduleItemRepository, placeRepository, locationEventRepository,
                new StraightLineTravelTimeEstimator(new FeasibilityProperties(25, Duration.ofMinutes(10))),
                bufferRuleService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // 預設沒有緩衝習慣;個別測試再覆寫
        lenient().when(bufferRuleService.recommendedBuffer(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Duration.ZERO);
    }

    /** 建立有 id 的行程(排除自身邏輯需要 id)。 */
    private ScheduleItem schedule(long id, String title, Instant start, Instant end, Long placeId) {
        ScheduleItem item = ScheduleItem.propose(title, start, end, placeId, NOW);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private Place place(long id, String name, double lat, double lon) {
        Place p = Place.create(name, null, lat, lon, null, NOW);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private void confirmedItems(ScheduleItem... items) {
        when(scheduleItemRepository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED))
                .thenReturn(List.of(items));
    }

    @Test
    void noConflictsIsFeasible() {
        confirmedItems();
        lenient().when(locationEventRepository.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.empty());

        FeasibilityResult result = service.check(
                schedule(1, "剪頭髮", NOW.plus(Duration.ofHours(3)), NOW.plus(Duration.ofHours(4)), null));

        assertThat(result.feasible()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void overlappingConfirmedScheduleIsFlagged() {
        confirmedItems(schedule(2, "開會", NOW.plus(Duration.ofHours(3)), NOW.plus(Duration.ofHours(5)), null));

        FeasibilityResult result = service.check(
                schedule(1, "剪頭髮", NOW.plus(Duration.ofHours(4)), NOW.plus(Duration.ofHours(6)), null));

        assertThat(result.feasible()).isFalse();
        assertThat(result.issues()).extracting(FeasibilityIssue::type)
                .containsExactly(FeasibilityIssue.Type.TIME_OVERLAP);
        assertThat(result.issues().get(0).relatedScheduleId()).isEqualTo(2L);
    }

    /** 使用者的原始案例:人在高雄,2 小時後台北的預約 → 擋下。 */
    @Test
    void kaohsiungToTaipeiInTwoHoursIsInfeasible() {
        confirmedItems();
        when(placeRepository.findById(10L)).thenReturn(Optional.of(place(10, "台北理髮廳", TPE_LAT, TPE_LON)));
        when(locationEventRepository.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.of(
                LocationEvent.record(LocationEventType.MANUAL_PING, KHH_LAT, KHH_LON, NOW, "test", NOW)));

        FeasibilityResult result = service.check(
                schedule(1, "剪頭髮", NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(3)), 10L));

        assertThat(result.feasible()).isFalse();
        assertThat(result.issues()).extracting(FeasibilityIssue::type)
                .containsExactly(FeasibilityIssue.Type.TRAVEL_FROM_CURRENT_LOCATION);
    }

    /** 同樣人在高雄,但預約在一天後 → 有充分時間移動,放行。 */
    @Test
    void kaohsiungToTaipeiTomorrowIsFeasible() {
        confirmedItems();
        when(placeRepository.findById(10L)).thenReturn(Optional.of(place(10, "台北理髮廳", TPE_LAT, TPE_LON)));
        when(locationEventRepository.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.of(
                LocationEvent.record(LocationEventType.MANUAL_PING, KHH_LAT, KHH_LON, NOW, "test", NOW)));

        FeasibilityResult result = service.check(
                schedule(1, "剪頭髮", NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)), 10L));

        assertThat(result.feasible()).isTrue();
    }

    /** 前一行程在高雄結束,30 分鐘後要到台北 → 趕不到。 */
    @Test
    void travelFromPreviousScheduleIsChecked() {
        ScheduleItem prev = schedule(2, "高雄拜訪", NOW, NOW.plus(Duration.ofHours(1)), 20L);
        confirmedItems(prev);
        when(placeRepository.findById(10L)).thenReturn(Optional.of(place(10, "台北理髮廳", TPE_LAT, TPE_LON)));
        when(placeRepository.findById(20L)).thenReturn(Optional.of(place(20, "高雄客戶", KHH_LAT, KHH_LON)));

        FeasibilityResult result = service.check(schedule(1, "剪頭髮",
                NOW.plus(Duration.ofMinutes(90)), NOW.plus(Duration.ofMinutes(150)), 10L));

        assertThat(result.feasible()).isFalse();
        assertThat(result.issues()).extracting(FeasibilityIssue::type)
                .containsExactly(FeasibilityIssue.Type.TRAVEL_FROM_PREVIOUS);
    }

    /** 結束後 20 分鐘要出現在高雄的下一行程 → 趕不上。 */
    @Test
    void travelToNextScheduleIsChecked() {
        ScheduleItem next = schedule(2, "高雄聚餐",
                NOW.plus(Duration.ofHours(4)).plus(Duration.ofMinutes(20)), NOW.plus(Duration.ofHours(6)), 20L);
        confirmedItems(next);
        when(placeRepository.findById(10L)).thenReturn(Optional.of(place(10, "台北理髮廳", TPE_LAT, TPE_LON)));
        when(placeRepository.findById(20L)).thenReturn(Optional.of(place(20, "高雄餐廳", KHH_LAT, KHH_LON)));
        lenient().when(locationEventRepository.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.empty());

        FeasibilityResult result = service.check(schedule(1, "剪頭髮",
                NOW.plus(Duration.ofHours(3)), NOW.plus(Duration.ofHours(4)), 10L));

        assertThat(result.feasible()).isFalse();
        assertThat(result.issues()).extracting(FeasibilityIssue::type)
                .containsExactly(FeasibilityIssue.Type.TRAVEL_TO_NEXT);
    }

    /** 邊界:空檔剛好夠(交通時間 < 空檔)→ 放行。同市區 5 公里、40 分鐘空檔。 */
    @Test
    void tightButSufficientGapIsFeasible() {
        // 5 公里 ÷ 25km/h = 12 分 + 10 分緩衝 = 22 分 < 40 分空檔
        ScheduleItem prev = schedule(2, "上一件事", NOW, NOW.plus(Duration.ofHours(1)), 20L);
        confirmedItems(prev);
        when(placeRepository.findById(10L)).thenReturn(Optional.of(place(10, "目的地", 25.0330, 121.5654)));
        when(placeRepository.findById(20L)).thenReturn(Optional.of(place(20, "出發地", 25.0780, 121.5654)));

        FeasibilityResult result = service.check(schedule(1, "剪頭髮",
                NOW.plus(Duration.ofMinutes(100)), NOW.plus(Duration.ofMinutes(160)), 10L));

        assertThat(result.feasible()).isTrue();
    }

    /**
     * 緩衝規則生效:同 tightButSufficientGapIsFeasible 的空檔(40 分,需 22 分),
     * 但出發地累積了 25 分鐘的超時習慣 → 有效空檔剩 15 分 → 擋下並註明已預留緩衝。
     */
    @Test
    void overrunBufferFromPreviousPlaceTightensTheGap() {
        ScheduleItem prev = schedule(2, "上一件事", NOW, NOW.plus(Duration.ofHours(1)), 20L);
        confirmedItems(prev);
        when(placeRepository.findById(10L)).thenReturn(Optional.of(place(10, "目的地", 25.0330, 121.5654)));
        when(placeRepository.findById(20L)).thenReturn(Optional.of(place(20, "出發地", 25.0780, 121.5654)));
        when(bufferRuleService.recommendedBuffer(20L)).thenReturn(Duration.ofMinutes(25));

        FeasibilityResult result = service.check(schedule(1, "剪頭髮",
                NOW.plus(Duration.ofMinutes(100)), NOW.plus(Duration.ofMinutes(160)), 10L));

        assertThat(result.feasible()).isFalse();
        assertThat(result.issues()).extracting(FeasibilityIssue::type)
                .containsExactly(FeasibilityIssue.Type.TRAVEL_FROM_PREVIOUS);
        assertThat(result.issues().get(0).message()).contains("已預留").contains("25 分鐘");
    }

    /** 無地點行程只檢查時間重疊,不做交通檢查。 */
    @Test
    void placelessScheduleSkipsTravelChecks() {
        confirmedItems();

        FeasibilityResult result = service.check(
                schedule(1, "線上會議", NOW.plus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(1)), null));

        assertThat(result.feasible()).isTrue();
    }
}
