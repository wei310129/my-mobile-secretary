package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.GeofenceRuleService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.Item;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskCreatedEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 自動綁定:任務建立 → 比對標題中的已知品項 → 自動綁到所有可購買地點。
 *
 * 這一層取代使用者手動 bind——「買排骨」建立時,系統自己知道要在全聯/菜市場提醒。
 * Phase 3 之後,品項比對會升級成 LLM 意圖理解,這裡的規則式比對是它的前身。
 */
@Component
public class TaskAutoBindListener {

    private static final Logger log = LoggerFactory.getLogger(TaskAutoBindListener.class);

    private final ItemService itemService;
    private final GeofenceRuleService geofenceRuleService;
    private final int autoBindRadiusMeters;

    public TaskAutoBindListener(ItemService itemService,
                                GeofenceRuleService geofenceRuleService,
                                @Value("${app.knowledge.auto-bind-radius-meters:200}") int autoBindRadiusMeters) {
        this.itemService = itemService;
        this.geofenceRuleService = geofenceRuleService;
        this.autoBindRadiusMeters = autoBindRadiusMeters;
    }

    /**
     * 與建任務同交易執行:回應返回前綁定已完成,任務與綁定要嘛全成、要嘛全不成。
     */
    @EventListener
    public void onTaskCreated(TaskCreatedEvent event) {
        // 蒐集所有被提到品項的可購買地點(去重:多個品項可能同地點)
        Set<Long> placeIds = new LinkedHashSet<>();
        for (Item item : itemService.findItemsMentionedIn(event.title())) {
            placeIds.addAll(item.getPlaceIds());
        }

        for (Long placeId : placeIds) {
            // 去重:已有同任務+同地點+ENTER 的規則(例如使用者手動綁過)就不重建
            if (geofenceRuleService.ruleExists(event.taskId(), placeId, TriggerType.ENTER)) {
                continue;
            }
            geofenceRuleService.createRule(event.taskId(), placeId, autoBindRadiusMeters, TriggerType.ENTER);
            log.info("Auto-bound task {} to place {} (radius {}m)", event.taskId(), placeId, autoBindRadiusMeters);
        }
    }
}
