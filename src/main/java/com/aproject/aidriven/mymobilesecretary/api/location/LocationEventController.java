package com.aproject.aidriven.mymobilesecretary.api.location;

import com.aproject.aidriven.mymobilesecretary.geo.application.LocationEventService;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 位置事件 API:Phase 1 由使用者以 API 模擬手機事件;之後 iOS App 直接打同一支。
 */
@RestController
@RequestMapping("/api/location-events")
public class LocationEventController {

    private final LocationEventService locationEventService;

    public LocationEventController(LocationEventService locationEventService) {
        this.locationEventService = locationEventService;
    }

    /** 回報位置事件 → 201,並回傳這次觸發的提醒 id 清單。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationEventRecordedResponse recordEvent(@Valid @RequestBody CreateLocationEventRequest request) {
        return LocationEventRecordedResponse.from(locationEventService.recordEvent(
                request.eventType(), request.latitude(), request.longitude(),
                request.occurredAt(), request.source()));
    }

    /** 列出事件(新到舊),查詢與除錯用。 */
    @GetMapping
    public List<LocationEventResponse> listEvents() {
        return locationEventService.listEvents().stream().map(LocationEventResponse::from).toList();
    }
}
