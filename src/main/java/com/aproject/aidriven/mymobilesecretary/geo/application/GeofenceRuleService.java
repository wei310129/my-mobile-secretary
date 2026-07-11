package com.aproject.aidriven.mymobilesecretary.geo.application;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.GeofenceRuleRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * geofence 規則 use case:把任務綁到地點。
 *
 * 模組邊界:任務存在與否透過 reminder 模組的 TaskService 查(不直接碰對方 repository),
 * 地點透過同模組的 PlaceService 查。
 */
@Service
@Transactional
public class GeofenceRuleService {

    private final GeofenceRuleRepository geofenceRuleRepository;
    private final PlaceService placeService;
    private final TaskService taskService;
    private final Clock clock;

    public GeofenceRuleService(GeofenceRuleRepository geofenceRuleRepository,
                               PlaceService placeService,
                               TaskService taskService,
                               Clock clock) {
        this.geofenceRuleRepository = geofenceRuleRepository;
        this.placeService = placeService;
        this.taskService = taskService;
        this.clock = clock;
    }

    /**
     * 建立 geofence 規則。
     *
     * 關鍵規則:task 與 place 必須都存在(任一不存在 → 404),
     * 半徑合法性由 GeofenceRule domain 把關(非法 → 422)。
     */
    public GeofenceRule createRule(Long taskId, Long placeId, int radiusMeters, TriggerType triggerType) {
        // 先驗證兩端都存在,避免建出指向幽靈資料的規則
        taskService.getTask(taskId);
        placeService.getPlace(placeId);

        GeofenceRule rule = GeofenceRule.create(taskId, placeId, radiusMeters, triggerType, Instant.now(clock));
        return geofenceRuleRepository.save(rule);
    }

    /** 同任務+同地點+同方向的規則是否已存在(自動綁定去重用)。 */
    @Transactional(readOnly = true)
    public boolean ruleExists(Long taskId, Long placeId, TriggerType triggerType) {
        return geofenceRuleRepository.existsByTaskIdAndPlaceIdAndTriggerType(taskId, placeId, triggerType);
    }

    /** 列出任務的全部規則。 */
    @Transactional(readOnly = true)
    public List<GeofenceRule> listRulesForTask(Long taskId) {
        return geofenceRuleRepository.findByTaskId(taskId);
    }
}
