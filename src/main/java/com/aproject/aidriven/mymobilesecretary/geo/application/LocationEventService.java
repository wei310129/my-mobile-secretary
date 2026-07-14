package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEventType;
import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationExitRecorded;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.GeofenceRuleRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.LocationEventRepository;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.ReminderTriggerService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 位置事件 use case:記錄事件 → geofence 比對 → 交給提醒觸發。
 *
 * 關鍵規則:本方法只接收離散 enter/exit/manual_ping 事件,不假設連續 GPS 追蹤。
 * 距離判斷全部在 geo 模組的 PostGIS 查詢內,這裡不自己算距離。
 */
@Service
@Transactional
public class LocationEventService {

    private final LocationEventRepository locationEventRepository;
    private final GeofenceRuleRepository geofenceRuleRepository;
    private final PlaceRepository placeRepository;
    private final ReminderTriggerService reminderTriggerService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public LocationEventService(LocationEventRepository locationEventRepository,
                                GeofenceRuleRepository geofenceRuleRepository,
                                PlaceRepository placeRepository,
                                ReminderTriggerService reminderTriggerService,
                                ApplicationEventPublisher eventPublisher,
                                Clock clock) {
        this.locationEventRepository = locationEventRepository;
        this.geofenceRuleRepository = geofenceRuleRepository;
        this.placeRepository = placeRepository;
        this.reminderTriggerService = reminderTriggerService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * 記錄位置事件並評估是否觸發提醒。
     *
     * @return 事件本身 + 實際觸發的提醒 id(被 debounce 或任務狀態擋下的不在內)
     */
    public LocationEventResult recordEvent(LocationEventType eventType, double latitude, double longitude,
                                           Instant occurredAt, String source) {
        LocationEvent event = locationEventRepository.save(
                LocationEvent.record(eventType, latitude, longitude, occurredAt, source, Instant.now(clock)));

        // MANUAL_PING 視同 ENTER(語意:「我人在這裡」)
        List<GeofenceRule> matches = geofenceRuleRepository.findEnabledRulesMatching(
                event.effectiveTriggerType().name(), latitude, longitude);

        // 一次撈齊命中規則的地點名稱,組出人類可讀的觸發原因
        Map<Long, String> placeNames = placeRepository
                .findAllById(matches.stream().map(GeofenceRule::getPlaceId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Place::getId, Place::getName, (a, b) -> a));

        // EXIT 廣播給其他模組(如行程結果追蹤):事件驅動,任一事件都可觸發重新評估。
        // 時間取自已存的 event(occurredAt 未提供時 domain 已補伺服器時間),不能用原始參數(可能為 null)
        if (eventType == LocationEventType.EXIT) {
            eventPublisher.publishEvent(new LocationExitRecorded(latitude, longitude, event.getOccurredAt()));
        }

        List<Long> reminderIds = new ArrayList<>();
        for (GeofenceRule rule : matches) {
            String reason = "%s geofence: %s".formatted(
                    event.effectiveTriggerType(), placeNames.getOrDefault(rule.getPlaceId(), "?"));
            reminderTriggerService.tryTrigger(rule.getTaskId(), reason)
                    .ifPresent(reminder -> reminderIds.add(reminder.getId()));
        }
        return new LocationEventResult(event, List.copyOf(reminderIds));
    }

    @Transactional(readOnly = true)
    public List<LocationEvent> listEvents() {
        return locationEventRepository.findAllByOrderByOccurredAtDesc();
    }

    /**
     * 記錄事件的結果。
     *
     * @param triggeredReminderIds 這次事件實際建立的提醒
     */
    public record LocationEventResult(LocationEvent event, List<Long> triggeredReminderIds) {
    }
}
