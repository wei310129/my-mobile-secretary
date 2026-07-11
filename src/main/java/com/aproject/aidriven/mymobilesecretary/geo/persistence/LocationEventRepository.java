package com.aproject.aidriven.mymobilesecretary.geo.persistence;

import com.aproject.aidriven.mymobilesecretary.geo.domain.LocationEvent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** LocationEvent 資料存取。 */
public interface LocationEventRepository extends JpaRepository<LocationEvent, Long> {

    /** 依發生時間新到舊列出(查詢/除錯用)。 */
    List<LocationEvent> findAllByOrderByOccurredAtDesc();

    /** 最後已知位置(可行性引擎的「我現在人在哪」來源)。 */
    Optional<LocationEvent> findTopByOrderByOccurredAtDesc();
}
