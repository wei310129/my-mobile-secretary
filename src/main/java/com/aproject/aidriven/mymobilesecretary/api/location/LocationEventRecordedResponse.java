package com.aproject.aidriven.mymobilesecretary.api.location;

import com.aproject.aidriven.mymobilesecretary.geo.application.LocationEventService.LocationEventResult;
import java.util.List;

/**
 * 回報位置事件的回應:事件本身 + 這次實際觸發的提醒 id。
 * 觸發清單讓手機端(與 Phase 1E 的手動測試)立刻知道這次回報有沒有命中。
 */
public record LocationEventRecordedResponse(
        LocationEventResponse event,
        List<Long> triggeredReminderIds
) {

    /** 由 service 結果轉成回應 DTO。 */
    public static LocationEventRecordedResponse from(LocationEventResult result) {
        return new LocationEventRecordedResponse(
                LocationEventResponse.from(result.event()),
                result.triggeredReminderIds());
    }
}
