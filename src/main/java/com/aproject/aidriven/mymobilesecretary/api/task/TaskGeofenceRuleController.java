package com.aproject.aidriven.mymobilesecretary.api.task;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任務底下的 geofence 規則 API:把任務綁到地點提醒。
 */
@RestController
@RequestMapping("/api/tasks/{taskId}/geofence-rules")
public class TaskGeofenceRuleController {

    private final GeofenceRuleService geofenceRuleService;

    public TaskGeofenceRuleController(GeofenceRuleService geofenceRuleService) {
        this.geofenceRuleService = geofenceRuleService;
    }

    /** 建立規則 → 201;task 或 place 不存在 → 404;半徑非法 → 400(Bean Validation)。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GeofenceRuleResponse createRule(@PathVariable Long taskId,
                                           @Valid @RequestBody CreateGeofenceRuleRequest request) {
        return GeofenceRuleResponse.from(geofenceRuleService.createRule(
                taskId, request.placeId(), request.radiusMeters(), request.triggerType()));
    }

    /** 列出任務的全部規則(含自動綁定產生的)。 */
    @GetMapping
    public List<GeofenceRuleResponse> listRules(@PathVariable Long taskId) {
        return geofenceRuleService.listRulesForTask(taskId).stream()
                .map(GeofenceRuleResponse::from)
                .toList();
    }
}
