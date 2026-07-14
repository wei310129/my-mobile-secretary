package com.aproject.aidriven.mymobilesecretary.schedule.persistence;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.FollowUpStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleFollowUp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** ScheduleFollowUp 資料存取。 */
public interface ScheduleFollowUpRepository extends JpaRepository<ScheduleFollowUp, Long> {

    Optional<ScheduleFollowUp> findByScheduleItemId(Long scheduleItemId);

    /** worker 輪詢:到期未發問的詢問,依到期先後排序。 */
    List<ScheduleFollowUp> findByStatusAndDueAtLessThanEqualOrderByDueAtAsc(
            FollowUpStatus status, Instant dueAt);

    /** 自然語言回報「剛才那個行程」時,對到最近一次發問的詢問(同批發問以 id 大者為新)。 */
    Optional<ScheduleFollowUp> findFirstByStatusOrderByAskedAtDescIdDesc(FollowUpStatus status);
}
