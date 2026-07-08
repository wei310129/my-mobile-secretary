package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** LocationEvent 資料存取。 */
public interface LocationEventRepository extends JpaRepository<LocationEvent, Long> {

    /** 依發生時間新到舊列出(查詢/除錯用)。 */
    List<LocationEvent> findAllByOrderByOccurredAtDesc();
}
