package com.aproject.aidriven.mymobilesecretary.schedule.application;

import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.planner.application.FeasibilityService;
import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityResult;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 行程 use case:提出 → 可行性驗算 →「要可行才放行」。
 *
 * 可行 → 自動 CONFIRMED;不可行 → 留在 PROPOSED,回傳警告與選項,
 * 由使用者決定:改時間(reschedule)/ 看過警告強制確認(confirm)/
 * 暫無想法(park → PENDING)/ 放棄(reject)。
 */
@Service
@Transactional
public class ScheduleService {

    private final ScheduleItemRepository scheduleItemRepository;
    private final FeasibilityService feasibilityService;
    private final PlaceService placeService;
    private final Clock clock;

    public ScheduleService(ScheduleItemRepository scheduleItemRepository,
                           FeasibilityService feasibilityService,
                           PlaceService placeService,
                           Clock clock) {
        this.scheduleItemRepository = scheduleItemRepository;
        this.feasibilityService = feasibilityService;
        this.placeService = placeService;
        this.clock = clock;
    }

    /** 提出新行程並驗算;可行自動放行。 */
    public ScheduleDecision createSchedule(String title, Instant startAt, Instant endAt, Long placeId) {
        return createSchedule(title, startAt, endAt, placeId, false);
    }

    /** 提出新行程並驗算;recurring = true 表示每週固定(結束後自動排下一週)。 */
    public ScheduleDecision createSchedule(String title, Instant startAt, Instant endAt,
                                           Long placeId, boolean recurring) {
        if (placeId != null) {
            placeService.getPlace(placeId); // 地點必須存在
        }
        Instant now = Instant.now(clock);
        ScheduleItem item = ScheduleItem.propose(title, startAt, endAt, placeId, now);
        if (recurring) {
            item.repeatWeekly(now);
        }
        item = scheduleItemRepository.save(item);
        return gate(item, now);
    }

    /** 設定/取消每週固定。 */
    public ScheduleItem setWeeklyRecurrence(Long scheduleId, boolean recurring) {
        ScheduleItem item = getSchedule(scheduleId);
        Instant now = Instant.now(clock);
        if (recurring) {
            item.repeatWeekly(now);
        } else {
            item.stopRepeating(now);
        }
        return item;
    }

    /**
     * 固定行程 rollover:已結束的 WEEKLY 行程(已確認/已完成)排出下一週的場次,
     * 下一週場次繼承固定屬性並照常走可行性關卡;已取消的視為使用者終止固定,不再排。
     * 同一場次只排一次(以「同標題+同週期+下一週開始時間」判重)。
     *
     * @return 這次新排出的場次數(測試與除錯用)
     */
    public int rolloverDueRecurringSchedules() {
        Instant now = Instant.now(clock);
        List<ScheduleItem> due = scheduleItemRepository
                .findByRecurrenceAndEndAtLessThanEqual(ScheduleItem.Recurrence.WEEKLY, now).stream()
                .filter(item -> item.getStatus() == ScheduleStatus.CONFIRMED
                        || item.getStatus() == ScheduleStatus.COMPLETED)
                .toList();
        int created = 0;
        for (ScheduleItem item : due) {
            Instant nextStart = item.getStartAt().plus(java.time.Duration.ofDays(7));
            if (scheduleItemRepository.existsByTitleAndRecurrenceAndStartAt(
                    item.getTitle(), ScheduleItem.Recurrence.WEEKLY, nextStart)) {
                continue;
            }
            createSchedule(item.getTitle(), nextStart,
                    item.getEndAt().plus(java.time.Duration.ofDays(7)), item.getPlaceId(), true);
            // 本場次交棒後改回單次,避免每輪重掃(下一週場次接手 WEEKLY 身分)
            item.stopRepeating(now);
            created++;
        }
        return created;
    }

    /** 改時間 → 回 PROPOSED 重新驗算;可行一樣自動放行。 */
    public ScheduleDecision reschedule(Long scheduleId, Instant newStartAt, Instant newEndAt) {
        ScheduleItem item = getSchedule(scheduleId);
        Instant now = Instant.now(clock);
        item.reschedule(newStartAt, newEndAt, now);
        return gate(item, now);
    }

    /** 「要可行才放行」的共同關卡。 */
    private ScheduleDecision gate(ScheduleItem item, Instant now) {
        FeasibilityResult result = feasibilityService.check(item);
        if (result.feasible()) {
            item.confirm(now);
        }
        return new ScheduleDecision(item, result);
    }

    /** 使用者看過警告後強制確認(例:已自行安排好交通)。 */
    public ScheduleItem confirmSchedule(Long scheduleId) {
        ScheduleItem item = getSchedule(scheduleId);
        item.confirm(Instant.now(clock));
        return item;
    }

    /** 暫無想法 → pending 池(之後空閒詢問會撈這裡)。 */
    public ScheduleItem parkSchedule(Long scheduleId) {
        ScheduleItem item = getSchedule(scheduleId);
        item.park(Instant.now(clock));
        return item;
    }

    /** 放棄不可行的提案。 */
    public ScheduleItem rejectSchedule(Long scheduleId) {
        ScheduleItem item = getSchedule(scheduleId);
        item.reject(Instant.now(clock));
        return item;
    }

    /** 取消已確認行程。 */
    public ScheduleItem cancelSchedule(Long scheduleId) {
        ScheduleItem item = getSchedule(scheduleId);
        item.cancel(Instant.now(clock));
        return item;
    }

    /** 完成行程(Phase 3 結果追蹤的入口)。 */
    public ScheduleItem completeSchedule(Long scheduleId) {
        ScheduleItem item = getSchedule(scheduleId);
        item.complete(Instant.now(clock));
        return item;
    }

    @Transactional(readOnly = true)
    public List<ScheduleItem> listSchedules(ScheduleStatus status) {
        return status == null
                ? scheduleItemRepository.findAllByOrderByStartAtAsc()
                : scheduleItemRepository.findByStatusOrderByStartAtAsc(status);
    }

    /**
     * 自然語言取消用的候選行程。只回傳仍可取消的行程;
     * 同名多筆會全數回傳,由呼叫端追問使用者,絕不任選一筆。
     */
    @Transactional(readOnly = true)
    public List<ScheduleItem> findCancelableSchedulesMatching(String keyword) {
        return findSchedulesMatching(keyword, EnumSet.of(ScheduleStatus.CONFIRMED, ScheduleStatus.PENDING));
    }

    /**
     * 自然語言改期用的候選行程。PROPOSED、CONFIRMED、PENDING 都能改期並重新驗算。
     */
    @Transactional(readOnly = true)
    public List<ScheduleItem> findReschedulableSchedulesMatching(String keyword) {
        return findSchedulesMatching(keyword,
                EnumSet.of(ScheduleStatus.PROPOSED, ScheduleStatus.CONFIRMED, ScheduleStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public ScheduleItem getSchedule(Long scheduleId) {
        return scheduleItemRepository.findById(scheduleId)
                .orElseThrow(() -> new NotFoundException("Schedule", scheduleId));
    }

    private List<ScheduleItem> findSchedulesMatching(String keyword, EnumSet<ScheduleStatus> statuses) {
        // 不分大小寫,同 TaskService 的關鍵字規則(英文行程才對得上)
        String needle = keyword == null ? "" : keyword.strip().toLowerCase();
        if (needle.isEmpty()) {
            return List.of();
        }
        return scheduleItemRepository.findByStatusInOrderByStartAtAsc(statuses).stream()
                .filter(item -> {
                    String title = item.getTitle().toLowerCase();
                    return title.contains(needle) || needle.contains(title);
                })
                .toList();
    }

    /** 行程 + 可行性驗算結果(建立/改時間的回傳)。 */
    public record ScheduleDecision(ScheduleItem item, FeasibilityResult feasibility) {
    }
}
