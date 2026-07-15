package com.aproject.aidriven.mymobilesecretary.integration.line;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** LineMessageLog 資料存取。 */
public interface LineMessageLogRepository extends JpaRepository<LineMessageLog, Long> {

    List<LineMessageLog> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
}
