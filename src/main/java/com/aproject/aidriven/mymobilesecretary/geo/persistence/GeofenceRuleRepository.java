package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import com.aproject.aidriven.mymobilesecretary.geo.domain.TriggerType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** GeofenceRule 資料存取。 */
public interface GeofenceRuleRepository extends JpaRepository<GeofenceRule, Long> {

    List<GeofenceRule> findByTaskId(Long taskId);

    /** 自動綁定去重:同任務+同地點+同方向是否已有規則。 */
    boolean existsByTaskIdAndPlaceIdAndTriggerType(Long taskId, Long placeId, TriggerType triggerType);

    /**
     * 找出事件座標命中的啟用規則:事件點落在「規則綁定地點」的半徑內,且觸發方向相符。
     *
     * 關鍵:cast 成 geography 後 ST_DWithin 以公尺為單位計算真實球面距離,
     * 不是經緯度的度數距離。距離規則集中在這一個查詢,其他程式不得自行算距離。
     */
    @Query(value = """
            SELECT gr.* FROM geofence_rule gr
            JOIN place p ON p.id = gr.place_id
            WHERE gr.enabled = TRUE
              AND gr.trigger_type = :triggerType
              AND ST_DWithin(
                    ST_SetSRID(ST_MakePoint(p.longitude, p.latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                    gr.radius_meters)
            """, nativeQuery = true)
    List<GeofenceRule> findEnabledRulesMatching(@Param("triggerType") String triggerType,
                                                @Param("latitude") double latitude,
                                                @Param("longitude") double longitude);
}
