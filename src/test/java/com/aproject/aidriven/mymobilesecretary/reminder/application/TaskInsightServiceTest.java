package com.aproject.aidriven.mymobilesecretary.reminder.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskInsightServiceTest {

    private static final Instant NOW = Instant.parse("2030-08-01T04:00:00Z"); // 台北 8/1 12:00

    @Mock
    private TaskService taskService;

    private TaskInsightService service;

    @BeforeEach
    void setUp() {
        service = new TaskInsightService(taskService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void recommendsOverdueBeforeLaterHighPriorityTask() {
        Task overdue = task("報帳", TaskPriority.LOW, NOW.minusSeconds(60),
                Task.Category.FINANCE, NOW.minusSeconds(7200));
        Task laterHigh = task("交企劃", TaskPriority.HIGH, NOW.plusSeconds(3600),
                Task.Category.WORK, NOW.minusSeconds(3600));
        when(taskService.listOpenTasks()).thenReturn(List.of(laterHigh, overdue));

        assertThat(service.recommendNext(null)).contains(overdue);
        assertThat(service.recommendNext(Task.Category.WORK)).contains(laterHigh);
    }

    @Test
    void groupsOnlyNonEmptyCategoriesInStableOrder() {
        Task work = task("交企劃", TaskPriority.NORMAL, null,
                Task.Category.WORK, NOW.minusSeconds(3600));
        Task shopping = task("買牛奶", TaskPriority.NORMAL, null,
                Task.Category.SHOPPING, NOW.minusSeconds(1800));
        when(taskService.listOpenTasks()).thenReturn(List.of(shopping, work));

        var groups = service.groupOpenByCategory();

        assertThat(groups.keySet()).containsExactly(Task.Category.WORK, Task.Category.SHOPPING);
        assertThat(groups.get(Task.Category.WORK)).containsExactly(work);
    }

    @Test
    void calculatesTodayProgressFromCompletedAndDueOpenTasks() {
        Task doneToday = completed("回覆信件", NOW.minusSeconds(3600));
        Task doneEarlier = completed("整理桌面", Instant.parse("2030-07-30T03:00:00Z"));
        Task dueToday = task("繳費", TaskPriority.NORMAL, NOW.plusSeconds(3600),
                Task.Category.FINANCE, NOW.minusSeconds(7200));
        Task dueEarlierThisWeek = task("採買", TaskPriority.NORMAL,
                Instant.parse("2030-07-31T03:00:00Z"), Task.Category.SHOPPING, NOW.minusSeconds(90000));
        when(taskService.listCompletedTasks()).thenReturn(List.of(doneToday, doneEarlier));
        when(taskService.listOpenTasks()).thenReturn(List.of(dueToday, dueEarlierThisWeek));

        var today = service.progress(TaskInsightService.Scope.TODAY);
        var week = service.progress(TaskInsightService.Scope.THIS_WEEK);

        assertThat(today).extracting(TaskInsightService.Progress::completed,
                        TaskInsightService.Progress::remaining,
                        TaskInsightService.Progress::total,
                        TaskInsightService.Progress::percentage)
                .containsExactly(1, 1, 2, 50);
        assertThat(week).extracting(TaskInsightService.Progress::completed,
                        TaskInsightService.Progress::remaining,
                        TaskInsightService.Progress::total,
                        TaskInsightService.Progress::percentage)
                .containsExactly(2, 2, 4, 50);
    }

    @Test
    void groupsOpenTasksIntoDueBuckets() {
        Task overdue = task("逾期", TaskPriority.NORMAL, NOW.minusSeconds(1),
                Task.Category.OTHER, NOW.minusSeconds(7200));
        Task today = task("今天", TaskPriority.NORMAL, NOW.plusSeconds(3600),
                Task.Category.OTHER, NOW.minusSeconds(7200));
        Task tomorrow = task("明天", TaskPriority.NORMAL, Instant.parse("2030-08-02T02:00:00Z"),
                Task.Category.OTHER, NOW.minusSeconds(7200));
        Task soon = task("七天內", TaskPriority.NORMAL, Instant.parse("2030-08-05T02:00:00Z"),
                Task.Category.OTHER, NOW.minusSeconds(7200));
        Task later = task("更晚", TaskPriority.NORMAL, Instant.parse("2030-08-20T02:00:00Z"),
                Task.Category.OTHER, NOW.minusSeconds(7200));
        Task noDue = task("無期限", TaskPriority.NORMAL, null,
                Task.Category.OTHER, NOW.minusSeconds(7200));
        when(taskService.listOpenTasks()).thenReturn(
                List.of(overdue, today, tomorrow, soon, later, noDue));

        var groups = service.groupOpenByDue();

        assertThat(groups.keySet()).containsExactly(
                TaskInsightService.DueBucket.OVERDUE,
                TaskInsightService.DueBucket.TODAY,
                TaskInsightService.DueBucket.TOMORROW,
                TaskInsightService.DueBucket.NEXT_SEVEN_DAYS,
                TaskInsightService.DueBucket.LATER,
                TaskInsightService.DueBucket.NO_DUE);
        assertThat(groups.get(TaskInsightService.DueBucket.TODAY)).containsExactly(today);
    }

    @Test
    void reportsLoadAndBusiestUpcomingDay() {
        Task overdueHigh = task("逾期急件", TaskPriority.HIGH, NOW.minusSeconds(60),
                Task.Category.WORK, NOW.minusSeconds(7200));
        Task today = task("今天", TaskPriority.NORMAL, NOW.plusSeconds(3600),
                Task.Category.WORK, NOW.minusSeconds(7200));
        Task tomorrowHigh = task("明天急件", TaskPriority.HIGH, Instant.parse("2030-08-02T02:00:00Z"),
                Task.Category.WORK, NOW.minusSeconds(7200));
        Task tomorrow = task("明天一般", TaskPriority.NORMAL, Instant.parse("2030-08-02T03:00:00Z"),
                Task.Category.PERSONAL, NOW.minusSeconds(7200));
        Task later = task("四天後", TaskPriority.NORMAL, Instant.parse("2030-08-05T04:00:00Z"),
                Task.Category.OTHER, NOW.minusSeconds(7200));
        when(taskService.listOpenTasks()).thenReturn(
                List.of(overdueHigh, today, tomorrowHigh, tomorrow, later));

        var todayLoad = service.load(TaskInsightService.LoadScope.TODAY);
        var threeDayLoad = service.load(TaskInsightService.LoadScope.NEXT_THREE_DAYS);

        assertThat(todayLoad).extracting(TaskInsightService.Load::remaining,
                        TaskInsightService.Load::overdue, TaskInsightService.Load::highPriority)
                .containsExactly(1, 1, 1);
        assertThat(threeDayLoad).extracting(TaskInsightService.Load::remaining,
                        TaskInsightService.Load::overdue, TaskInsightService.Load::highPriority)
                .containsExactly(3, 1, 2);
        assertThat(service.busiestDueDay()).hasValueSatisfying(load -> {
            assertThat(load.date()).isEqualTo(LocalDate.of(2030, 8, 2));
            assertThat(load.count()).isEqualTo(2);
        });
    }

    private static Task task(String title, TaskPriority priority, Instant dueAt,
                             Task.Category category, Instant createdAt) {
        return Task.create(title, null, priority, dueAt, category, Task.Recurrence.NONE,
                Task.ConditionType.NONE, null, createdAt);
    }

    private static Task completed(String title, Instant completedAt) {
        Task task = task(title, TaskPriority.NORMAL, null, Task.Category.OTHER,
                completedAt.minusSeconds(60));
        task.confirm(completedAt);
        return task;
    }
}
