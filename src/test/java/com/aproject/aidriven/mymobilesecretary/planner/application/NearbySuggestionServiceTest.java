package com.aproject.aidriven.mymobilesecretary.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEventType;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.GeofenceRuleRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
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

/**
 * 順路建議規則測試:沒位置要先問、距離內排序列出、太遠不算順路、
 * 時間窗內的下個行程要先講、天氣建議附加。
 */
@ExtendWith(MockitoExtension.class)
class NearbySuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-15T02:00:00Z");
    /** 使用者位置(新店)。 */
    private static final double USER_LAT = 24.9680, USER_LON = 121.5410;

    @Mock
    private TaskService taskService;
    @Mock
    private GeofenceRuleRepository geofenceRuleRepository;
    @Mock
    private PlaceRepository placeRepository;
    @Mock
    private LocationEventRepository locationEventRepository;
    @Mock
    private ScheduleItemRepository scheduleItemRepository;
    @Mock
    private WeatherAdvisoryService weatherAdvisoryService;

    private NearbySuggestionService service;

    @BeforeEach
    void setUp() {
        service = new NearbySuggestionService(taskService, geofenceRuleRepository, placeRepository,
                locationEventRepository, scheduleItemRepository, weatherAdvisoryService,
                new SuggestionProperties(Duration.ofHours(3), 2000, 5),
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(weatherAdvisoryService.currentAdvisory()).thenReturn(Optional.empty());
        lenient().when(scheduleItemRepository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED))
                .thenReturn(List.of());
    }

    private void userAt(double lat, double lon) {
        when(locationEventRepository.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.of(
                LocationEvent.record(LocationEventType.MANUAL_PING, lat, lon, NOW, "test", NOW)));
    }

    private Task task(long id, String title) {
        Task task = mock(Task.class);
        lenient().when(task.getId()).thenReturn(id);
        lenient().when(task.getTitle()).thenReturn(title);
        return task;
    }

    private Place place(long id, String name, double lat, double lon) {
        Place place = mock(Place.class);
        lenient().when(place.getId()).thenReturn(id);
        lenient().when(place.getName()).thenReturn(name);
        lenient().when(place.getLatitude()).thenReturn(lat);
        lenient().when(place.getLongitude()).thenReturn(lon);
        return place;
    }

    private GeofenceRule ruleToPlace(long placeId) {
        GeofenceRule rule = mock(GeofenceRule.class);
        when(rule.getPlaceId()).thenReturn(placeId);
        return rule;
    }

    @Test
    void withoutLocationAsksForPing() {
        when(locationEventRepository.findTopByOrderByOccurredAtDesc()).thenReturn(Optional.empty());

        assertThat(service.suggest()).contains("不知道你在哪");
    }

    /** 500 公尺內的任務列出、5 公里外的不算順路。 */
    @Test
    void nearTaskIsSuggestedFarTaskIsNot() {
        userAt(USER_LAT, USER_LON);
        Task near = task(1, "買排骨");
        Task far = task(2, "還書");
        GeofenceRule nearRule = ruleToPlace(10L);
        GeofenceRule farRule = ruleToPlace(20L);
        when(taskService.listOpenTasks()).thenReturn(List.of(near, far));
        when(geofenceRuleRepository.findByTaskId(1L)).thenReturn(List.of(nearRule));
        when(geofenceRuleRepository.findByTaskId(2L)).thenReturn(List.of(farRule));
        // 全聯約 500 公尺(緯度 +0.0045);圖書館約 5 公里(緯度 +0.045)
        Place supermarket = place(10, "全聯", USER_LAT + 0.0045, USER_LON);
        Place library = place(20, "圖書館", USER_LAT + 0.045, USER_LON);
        when(placeRepository.findAllById(List.of(10L))).thenReturn(List.of(supermarket));
        when(placeRepository.findAllById(List.of(20L))).thenReturn(List.of(library));

        String message = service.suggest();

        assertThat(message).contains("買排骨").contains("全聯");
        assertThat(message).doesNotContain("還書");
    }

    /** 沒綁地點的任務算不了順路,不出現在建議。 */
    @Test
    void unboundTasksYieldEmptySuggestion() {
        userAt(USER_LAT, USER_LON);
        Task unbound = task(1, "打電話給媽媽");
        when(taskService.listOpenTasks()).thenReturn(List.of(unbound));
        when(geofenceRuleRepository.findByTaskId(1L)).thenReturn(List.of());

        assertThat(service.suggest()).contains("沒有綁定地點的待辦");
    }

    /** 3 小時窗內有已確認行程 → 開頭先講「幾點前有空檔」。 */
    @Test
    void headerMentionsNextScheduleInWindow() {
        userAt(USER_LAT, USER_LON);
        when(taskService.listOpenTasks()).thenReturn(List.of());
        ScheduleItem next = ScheduleItem.propose("拿包裹",
                NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)), null, NOW);
        next.confirm(NOW);
        when(scheduleItemRepository.findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED))
                .thenReturn(List.of(next));

        assertThat(service.suggest()).contains("拿包裹").contains("在那之前有空檔");
    }

    /** 天氣建議(下雨/高溫)附在建議後面。 */
    @Test
    void weatherAdvisoryIsAppended() {
        userAt(USER_LAT, USER_LON);
        when(taskService.listOpenTasks()).thenReturn(List.of());
        when(weatherAdvisoryService.currentAdvisory())
                .thenReturn(Optional.of("降雨機率 70%,記得帶傘、東西別買太多"));

        assertThat(service.suggest()).contains("☔").contains("降雨機率 70%");
    }
}
