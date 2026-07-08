package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.GeofenceRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** GeofenceRule 資料存取。 */
public interface GeofenceRuleRepository extends JpaRepository<GeofenceRule, Long> {

    List<GeofenceRule> findByTaskId(Long taskId);
}
