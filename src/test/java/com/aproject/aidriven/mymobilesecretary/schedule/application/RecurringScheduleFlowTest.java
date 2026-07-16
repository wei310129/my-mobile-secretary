package com.aproject.aidriven.mymobilesecretary.schedule.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleItem;
import com.aproject.aidriven.mymobilesecretary.schedule.domain.ScheduleStatus;
import com.aproject.aidriven.mymobilesecretary.schedule.persistence.ScheduleItemRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 固定行程 rollover 測試(真實 DB):
 * 結束的每週行程自動排下一週、身分交棒、判重不重排、已取消不再排。
 */
class RecurringScheduleFlowTest extends IntegrationTestBase {

    @Autowired
    private ScheduleItemRepository scheduleItemRepository;
    @Autowired
    private ScheduleService scheduleService;

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    /** 各測試用不同時段,避免 rollover 出的下一週場次互相撞期(共用 DB)。 */
    private ScheduleItem saveWeeklyEnded(String title, ScheduleStatus finalStatus, int hoursAgo) {
        Instant now = now();
        ScheduleItem item = ScheduleItem.propose(title,
                now.minus(Duration.ofHours(hoursAgo)), now.minus(Duration.ofHours(hoursAgo - 1)), null, now);
        item.confirm(now);
        item.repeatWeekly(now);
        if (finalStatus == ScheduleStatus.COMPLETED) {
            item.complete(now);
        } else if (finalStatus == ScheduleStatus.CANCELED) {
            item.cancel(now);
        }
        return scheduleItemRepository.save(item);
    }

    /** 結束的每週行程 → 自動排下一週(同時段+7天、繼承固定身分),原場次交棒改回單次。 */
    @Test
    void endedWeeklyScheduleRollsOverToNextWeek() {
        ScheduleItem thisWeek = saveWeeklyEnded("固定測試每週送課", ScheduleStatus.COMPLETED, 3);

        int created = scheduleService.rolloverDueRecurringSchedules();

        assertThat(created).isGreaterThanOrEqualTo(1);
        List<ScheduleItem> all = scheduleItemRepository.findAllByOrderByStartAtAsc();
        ScheduleItem nextWeek = all.stream()
                .filter(i -> i.getTitle().equals("固定測試每週送課")
                        && i.getStartAt().equals(thisWeek.getStartAt().plus(Duration.ofDays(7))))
                .findFirst().orElseThrow();
        // 下一週場次接手 WEEKLY 身分並通過可行性關卡(未來時段、無衝突 → CONFIRMED)
        assertThat(nextWeek.getRecurrence()).isEqualTo(ScheduleItem.Recurrence.WEEKLY);
        assertThat(nextWeek.getStatus()).isEqualTo(ScheduleStatus.CONFIRMED);
        // 原場次交棒後改回單次,不會每輪重掃
        assertThat(scheduleItemRepository.findById(thisWeek.getId()).orElseThrow().getRecurrence())
                .isEqualTo(ScheduleItem.Recurrence.NONE);
    }

    /** 判重:同一場次跑兩輪 rollover 只會排一次。 */
    @Test
    void rolloverIsIdempotent() {
        ScheduleItem item = saveWeeklyEnded("固定測試判重會議", ScheduleStatus.COMPLETED, 6);

        scheduleService.rolloverDueRecurringSchedules();
        scheduleService.rolloverDueRecurringSchedules();

        long count = scheduleItemRepository.findAllByOrderByStartAtAsc().stream()
                .filter(i -> i.getTitle().equals("固定測試判重會議")
                        && i.getStartAt().equals(item.getStartAt().plus(Duration.ofDays(7))))
                .count();
        assertThat(count).isEqualTo(1);
    }

    /** 已取消的每週行程 = 使用者終止固定,不再排下一週。 */
    @Test
    void canceledWeeklyScheduleDoesNotRollOver() {
        ScheduleItem canceled = saveWeeklyEnded("固定測試已取消", ScheduleStatus.CANCELED, 9);

        scheduleService.rolloverDueRecurringSchedules();

        boolean nextExists = scheduleItemRepository.findAllByOrderByStartAtAsc().stream()
                .anyMatch(i -> i.getTitle().equals("固定測試已取消")
                        && i.getStartAt().equals(canceled.getStartAt().plus(Duration.ofDays(7))));
        assertThat(nextExists).isFalse();
    }

    /** 上班日固定:週五結束後跳到週一,其他平日則排隔天。 */
    @Test
    void endedWorkdayScheduleRollsOverToNextWeekday() {
        Instant now = now();
        ScheduleItem current = ScheduleItem.propose("固定測試上班日通勤",
                now.minus(Duration.ofHours(12)), now.minus(Duration.ofHours(11)), null, now);
        current.confirm(now);
        current.repeat(ScheduleItem.Recurrence.WEEKDAYS, now);
        current.complete(now);
        scheduleItemRepository.save(current);
        ZonedDateTime currentStart = ZonedDateTime.ofInstant(current.getStartAt(), ZoneId.of("Asia/Taipei"));
        ZonedDateTime expectedStart = currentStart.plusDays(1);
        while (expectedStart.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || expectedStart.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            expectedStart = expectedStart.plusDays(1);
        }

        scheduleService.rolloverDueRecurringSchedules();

        Instant expected = expectedStart.toInstant();
        ScheduleItem next = scheduleItemRepository.findAllByOrderByStartAtAsc().stream()
                .filter(item -> item.getTitle().equals("固定測試上班日通勤"))
                .filter(item -> item.getStartAt().equals(expected))
                .findFirst().orElseThrow();
        assertThat(next.getRecurrence()).isEqualTo(ScheduleItem.Recurrence.WEEKDAYS);
    }

    /** 截止日含當日：下一場剛好落在截止日，仍要建立並把截止日交棒。 */
    @Test
    void weeklyScheduleIncludesOccurrenceOnCutoffDate() {
        Instant now = now();
        ScheduleItem current = ScheduleItem.propose("固定測試截止日邊界",
                now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(2)), null, now);
        LocalDate cutoff = current.getStartAt().plus(Duration.ofDays(7))
                .atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
        current.confirm(now);
        current.repeat(ScheduleItem.Recurrence.WEEKLY, cutoff, now);
        current.complete(now);
        scheduleItemRepository.save(current);

        scheduleService.rolloverDueRecurringSchedules();

        ScheduleItem next = scheduleItemRepository.findAllByOrderByStartAtAsc().stream()
                .filter(item -> item.getTitle().equals("固定測試截止日邊界"))
                .filter(item -> item.getStartAt().equals(current.getStartAt().plus(Duration.ofDays(7))))
                .findFirst().orElseThrow();
        assertThat(next.getRecurrence()).isEqualTo(ScheduleItem.Recurrence.WEEKLY);
        assertThat(next.getRecurrenceUntil()).isEqualTo(cutoff);
    }

    /** 下一場超過截止日：不建立新場次，固定規則自然結束並保留截止紀錄。 */
    @Test
    void weeklyScheduleStopsBeforeOccurrenceAfterCutoff() {
        Instant now = now();
        ScheduleItem current = ScheduleItem.propose("固定測試截止後停止",
                now.minus(Duration.ofHours(5)), now.minus(Duration.ofHours(4)), null, now);
        LocalDate cutoff = current.getStartAt().atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
        current.confirm(now);
        current.repeat(ScheduleItem.Recurrence.WEEKLY, cutoff, now);
        current.complete(now);
        scheduleItemRepository.save(current);

        scheduleService.rolloverDueRecurringSchedules();

        boolean nextExists = scheduleItemRepository.findAllByOrderByStartAtAsc().stream()
                .anyMatch(item -> item.getTitle().equals("固定測試截止後停止")
                        && item.getStartAt().equals(current.getStartAt().plus(Duration.ofDays(7))));
        ScheduleItem ended = scheduleItemRepository.findById(current.getId()).orElseThrow();
        assertThat(nextExists).isFalse();
        assertThat(ended.getRecurrence()).isEqualTo(ScheduleItem.Recurrence.NONE);
        assertThat(ended.getRecurrenceUntil()).isEqualTo(cutoff);
    }
}
