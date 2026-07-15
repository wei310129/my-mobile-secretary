package com.aproject.aidriven.mymobilesecretary.planner.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeoDistance;
import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 順路建議(「待會可以順便做什麼」):確定性規則的整體安排,LLM 不參與計算。
 *
 * 綜合四種資訊(使用者 2026-07-15 拍板):
 * 1. 未來 3 小時的已確認行程(還剩多少空檔)。
 * 2. 未完成任務 + 其綁定地點。
 * 3. 最後已知位置 → 距離排序,太遠的不算順路。
 * 4. 天氣建議(現有 WeatherAdvisoryService,查不到就略過)。
 */
@Service
@Transactional(readOnly = true)
public class NearbySuggestionService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TaskService taskService;
    private final GeofenceRuleRepository geofenceRuleRepository;
    private final PlaceRepository placeRepository;
    private final LocationEventRepository locationEventRepository;
    private final ScheduleItemRepository scheduleItemRepository;
    private final WeatherAdvisoryService weatherAdvisoryService;
    private final SuggestionProperties properties;
    private final Clock clock;

    public NearbySuggestionService(TaskService taskService,
                                   GeofenceRuleRepository geofenceRuleRepository,
                                   PlaceRepository placeRepository,
                                   LocationEventRepository locationEventRepository,
                                   ScheduleItemRepository scheduleItemRepository,
                                   WeatherAdvisoryService weatherAdvisoryService,
                                   SuggestionProperties properties,
                                   Clock clock) {
        this.taskService = taskService;
        this.geofenceRuleRepository = geofenceRuleRepository;
        this.placeRepository = placeRepository;
        this.locationEventRepository = locationEventRepository;
        this.scheduleItemRepository = scheduleItemRepository;
        this.weatherAdvisoryService = weatherAdvisoryService;
        this.properties = properties;
        this.clock = clock;
    }

    /** 產生「待會順便做」建議訊息;一定回傳可讀文字,不丟例外。 */
    public String suggest() {
        Instant now = Instant.now(clock);
        Instant windowEnd = now.plus(properties.window());

        Optional<LocationEvent> lastLocation = locationEventRepository.findTopByOrderByOccurredAtDesc();
        if (lastLocation.isEmpty()) {
            return "我還不知道你在哪(沒有位置回報),先回報一下位置我才能算哪些事順路。";
        }
        double lat = lastLocation.get().getLatitude();
        double lon = lastLocation.get().getLongitude();

        StringBuilder message = new StringBuilder(header(now, windowEnd));

        List<TaskAtPlace> nearby = nearbyTasks(lat, lon);
        if (nearby.isEmpty()) {
            message.append("\n附近 %.0f 公尺內沒有綁定地點的待辦。".formatted(properties.maxDistanceMeters()));
        } else {
            message.append("\n順路可以做:");
            for (int i = 0; i < nearby.size(); i++) {
                TaskAtPlace candidate = nearby.get(i);
                message.append("\n%d. 「%s」— %s(約 %d 公尺)".formatted(
                        i + 1, candidate.task().getTitle(),
                        candidate.place().getName(), Math.round(candidate.distanceMeters())));
            }
        }

        weatherAdvisoryService.currentAdvisory()
                .ifPresent(advisory -> message.append("\n☔ ").append(advisory));
        return message.toString();
    }

    /** 時間窗開頭:先講清楚有多少空檔,建議才有依據。 */
    private String header(Instant now, Instant windowEnd) {
        long windowHours = properties.window().toHours();
        Optional<ScheduleItem> next = scheduleItemRepository
                .findByStatusOrderByStartAtAsc(ScheduleStatus.CONFIRMED).stream()
                .filter(item -> item.getStartAt().isAfter(now) && item.getStartAt().isBefore(windowEnd))
                .findFirst();
        return next.map(item -> "接下來 %d 小時內,%s 有「%s」,在那之前有空檔。".formatted(
                        windowHours,
                        ZonedDateTime.ofInstant(item.getStartAt(), TAIPEI).format(TIME),
                        item.getTitle()))
                .orElse("接下來 %d 小時沒有已確認行程,整段都有空。".formatted(windowHours));
    }

    /** 未完成任務 × 綁定地點 → 距離排序,取順路範圍內前幾件。 */
    private List<TaskAtPlace> nearbyTasks(double lat, double lon) {
        return taskService.listOpenTasks().stream()
                .flatMap(task -> nearestBoundPlace(task, lat, lon).stream())
                .filter(candidate -> candidate.distanceMeters() <= properties.maxDistanceMeters())
                .sorted(Comparator.comparingDouble(TaskAtPlace::distanceMeters))
                .limit(properties.limit())
                .toList();
    }

    /** 任務綁了多個地點時取最近的那個(距離計算集中在 geo 模組)。 */
    private Optional<TaskAtPlace> nearestBoundPlace(Task task, double lat, double lon) {
        List<Long> placeIds = geofenceRuleRepository.findByTaskId(task.getId()).stream()
                .map(GeofenceRule::getPlaceId).distinct().toList();
        if (placeIds.isEmpty()) {
            return Optional.empty();
        }
        return placeRepository.findAllById(placeIds).stream()
                .map(place -> new TaskAtPlace(task, place,
                        GeoDistance.metersBetween(lat, lon, place.getLatitude(), place.getLongitude())))
                .min(Comparator.comparingDouble(TaskAtPlace::distanceMeters));
    }

    private record TaskAtPlace(Task task, Place place, double distanceMeters) {
    }
}
