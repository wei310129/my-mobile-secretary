package com.aproject.aidriven.mymobilesecretary.schedule.persistence;

import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** ScheduleItem 資料存取。 */
public interface ScheduleItemRepository extends JpaRepository<ScheduleItem, Long> {

    List<ScheduleItem> findAllByOrderByStartAtAsc();

    List<ScheduleItem> findByStatusOrderByStartAtAsc(ScheduleStatus status);

    List<ScheduleItem> findByStatusInOrderByStartAtAsc(Collection<ScheduleStatus> statuses);

    /** 固定行程 rollover:已結束的每週行程。 */
    List<ScheduleItem> findByRecurrenceAndEndAtLessThanEqual(
            ScheduleItem.Recurrence recurrence, Instant endAt);

    /** rollover 判重:下一週場次是否已排。 */
    boolean existsByTitleAndRecurrenceAndStartAt(
            String title, ScheduleItem.Recurrence recurrence, Instant startAt);

    /** 空閒偵測:某狀態的行程是否與 [from, until) 時間窗重疊。 */
    boolean existsByStatusAndStartAtLessThanAndEndAtGreaterThan(
            ScheduleStatus status, Instant until, Instant from);

    /** 結果追蹤(時間路徑):在 [from, to] 內結束的行程(限 lookback 窗,避免舊資料湧入詢問)。 */
    List<ScheduleItem> findByStatusAndEndAtBetween(ScheduleStatus status, Instant from, Instant to);

    /** 結果追蹤(GPS 路徑):已開始、結束不早於 from、地點命中的行程。 */
    List<ScheduleItem> findByStatusAndPlaceIdInAndStartAtLessThanEqualAndEndAtGreaterThanEqual(
            ScheduleStatus status, List<Long> placeIds, Instant startedBy, Instant endsAfter);
}
