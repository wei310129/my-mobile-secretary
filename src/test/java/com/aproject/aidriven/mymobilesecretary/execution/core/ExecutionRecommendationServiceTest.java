package com.aproject.aidriven.mymobilesecretary.execution.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionRecommendationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-30T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void explicitCalendarBufferPlacesAdoptedFixedEventInNow() {
        var event =
                calendar(
                        "calendar-1",
                        NOW.plus(Duration.ofMinutes(10)),
                        false,
                        true,
                        true,
                        Duration.ofMinutes(20));

        var result = recommend(CLOCK, snapshot(List.of(), List.of(event)));

        assertThat(result.now()).get().satisfies(
                item -> {
                    assertThat(item.kind()).isEqualTo(ExecutionItemKind.CALENDAR);
                    assertThat(item.stableId()).isEqualTo("calendar-1");
                    assertThat(item.notUrgent()).isFalse();
                });
        assertThat(result.next()).isEmpty();
        assertThat(result.later().count()).isZero();
    }

    @Test
    void missingBufferUsesFifteenMinuteDisplayOnlyBoundary() {
        var event =
                calendar(
                        "calendar-1",
                        NOW.plus(Duration.ofMinutes(15)),
                        false,
                        true,
                        true,
                        null);

        var result = recommend(CLOCK, snapshot(List.of(), List.of(event)));

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("calendar-1");
        assertThat(event.prepBuffer()).isNull();
    }

    @Test
    void oneSecondBeforeDefaultPrepWindowUsesNonUrgentTaskFallback() {
        var beforeBoundary = Clock.fixed(NOW.minusSeconds(1), ZoneOffset.UTC);
        var event =
                calendar(
                        "calendar-1",
                        NOW.plus(Duration.ofMinutes(15)),
                        false,
                        true,
                        true,
                        null);
        var task =
                task(
                        "task-1",
                        NOW.plus(Duration.ofDays(2)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);

        var result = recommend(beforeBoundary, snapshot(List.of(task), List.of(event)));

        assertThat(result.now()).get().satisfies(
                item -> {
                    assertThat(item.stableId()).isEqualTo("task-1");
                    assertThat(item.notUrgent()).isTrue();
                });
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("calendar-1");
    }

    @Test
    void imminentCalendarWinsOverOverdueHighPriorityTask() {
        var event =
                calendar(
                        "calendar-1",
                        NOW.plus(Duration.ofMinutes(5)),
                        false,
                        true,
                        true,
                        Duration.ofMinutes(10));
        var task =
                task(
                        "task-1",
                        NOW.minus(Duration.ofHours(2)),
                        ExecutionPriority.HIGH,
                        true,
                        false);

        var result = recommend(CLOCK, snapshot(List.of(task), List.of(event)));

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("calendar-1");
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-1");
    }

    @Test
    void explicitReminderDefinesPrepWindowWithoutCreatingNotificationState() {
        var event =
                new ExecutionCalendarItem(
                        "calendar-reminder",
                        "Calendar reminder",
                        NOW.plus(Duration.ofMinutes(30)),
                        NOW.plus(Duration.ofMinutes(60)),
                        false,
                        true,
                        true,
                        NOW,
                        null);

        var result = recommend(CLOCK, snapshot(List.of(), List.of(event)));

        assertThat(result.now()).get().satisfies(
                item -> {
                    assertThat(item.stableId()).isEqualTo("calendar-reminder");
                    assertThat(item.notUrgent()).isFalse();
                });
        assertThat(event.explicitPrepStartsAt()).isEqualTo(NOW);
        assertThat(event.prepBuffer()).isNull();
    }

    @Test
    void flexibleCalendarInsideDefaultWindowDoesNotBecomeUrgent() {
        var event =
                calendar(
                        "calendar-flexible",
                        NOW.plus(Duration.ofMinutes(5)),
                        false,
                        false,
                        true,
                        null);
        var overdue =
                task(
                        "task-overdue",
                        NOW.minus(Duration.ofMinutes(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);

        var result = recommend(CLOCK, snapshot(List.of(overdue), List.of(event)));

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-overdue");
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("calendar-flexible");
    }

    @Test
    void activeFixedCalendarRemainsInNowUntilItEnds() {
        var event =
                new ExecutionCalendarItem(
                        "calendar-1",
                        "Calendar calendar-1",
                        NOW.minus(Duration.ofMinutes(5)),
                        NOW.plus(Duration.ofMinutes(25)),
                        false,
                        true,
                        true,
                        null,
                        Duration.ofMinutes(10));

        var result = recommend(CLOCK, snapshot(List.of(), List.of(event)));

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("calendar-1");
    }

    @Test
    void blockedAndClosedTasksAndUnadoptedCalendarAreExcluded() {
        var blocked =
                task(
                        "task-blocked",
                        NOW.minusSeconds(1),
                        ExecutionPriority.HIGH,
                        true,
                        true);
        var closed =
                task(
                        "task-closed",
                        NOW.minusSeconds(1),
                        ExecutionPriority.HIGH,
                        false,
                        false);
        var unadopted =
                calendar(
                        "calendar-unadopted",
                        NOW.plus(Duration.ofMinutes(5)),
                        false,
                        true,
                        false,
                        Duration.ofMinutes(10));

        var result =
                recommend(
                        CLOCK,
                        snapshot(List.of(blocked, closed), List.of(unadopted)));

        assertThat(result.now()).isEmpty();
        assertThat(result.next()).isEmpty();
        assertThat(result.later().count()).isZero();
        assertThat(result.later().orderedItems()).isEmpty();
    }

    @Test
    void allDayCalendarIsNotTreatedAsFixedImminentEvent() {
        var allDay =
                calendar(
                        "calendar-all-day",
                        NOW.plus(Duration.ofMinutes(5)),
                        true,
                        true,
                        true,
                        Duration.ofMinutes(10));
        var task =
                task(
                        "task-1",
                        NOW.plus(Duration.ofDays(1)),
                        ExecutionPriority.LOW,
                        true,
                        false);

        var result = recommend(CLOCK, snapshot(List.of(task), List.of(allDay)));

        assertThat(result.now()).get().satisfies(
                item -> {
                    assertThat(item.stableId()).isEqualTo("task-1");
                    assertThat(item.notUrgent()).isTrue();
                });
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("calendar-all-day");
    }

    @Test
    void noUrgentItemFallsBackToBestOpenTask() {
        var lowerPriority =
                task(
                        "task-low",
                        null,
                        ExecutionPriority.LOW,
                        true,
                        false);
        var earlierDeadline =
                task(
                        "task-earlier",
                        NOW.plus(Duration.ofDays(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);

        var result =
                recommend(
                        CLOCK,
                        snapshot(List.of(lowerPriority, earlierDeadline), List.of()));

        assertThat(result.now()).get().satisfies(
                item -> {
                    assertThat(item.stableId()).isEqualTo("task-earlier");
                    assertThat(item.notUrgent()).isTrue();
                });
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-low");
    }

    @Test
    void nonUrgentRemainingItemsUseTimePressureBeforeItemKind() {
        var fallback =
                task(
                        "task-fallback",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);
        var secondTask =
                task(
                        "task-second",
                        NOW.plus(Duration.ofHours(2)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);
        var laterCalendar =
                calendar(
                        "calendar-later",
                        NOW.plus(Duration.ofHours(3)),
                        false,
                        true,
                        true,
                        null);

        var result =
                recommend(
                        CLOCK,
                        snapshot(
                                List.of(secondTask, fallback),
                                List.of(laterCalendar)));

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-fallback");
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-second");
        assertThat(result.later().orderedItems())
                .extracting(ExecutionRecommendationItem::stableId)
                .containsExactly("calendar-later");
    }

    @Test
    void nowNextAndLaterAreUniqueAndLaterKeepsStableExpandableOrder() {
        var first =
                task(
                        "task-a",
                        NOW.minus(Duration.ofHours(1)),
                        ExecutionPriority.HIGH,
                        true,
                        false);
        var second =
                task(
                        "task-b",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);
        var third =
                task(
                        "task-c",
                        NOW.plus(Duration.ofHours(2)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);
        var fourth =
                task(
                        "task-d",
                        null,
                        ExecutionPriority.LOW,
                        true,
                        false);

        var result =
                recommend(
                        CLOCK,
                        snapshot(List.of(fourth, third, first, second), List.of()));

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-a");
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-b");
        assertThat(result.later().count()).isEqualTo(2);
        assertThat(result.later().orderedItems())
                .extracting(ExecutionRecommendationItem::stableId)
                .containsExactly("task-c", "task-d");
        assertThat(
                        List.of(
                                result.now().orElseThrow().stableId(),
                                result.next().orElseThrow().stableId(),
                                result.later().orderedItems().get(0).stableId(),
                                result.later().orderedItems().get(1).stableId()))
                .doesNotHaveDuplicates();
    }

    @Test
    void replayAndInputOrderProduceTheSameDeterministicResult() {
        var first =
                task(
                        "task-a",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);
        var second =
                task(
                        "task-b",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);
        var service =
                new ExecutionRecommendationService(
                        CLOCK, () -> snapshot(List.of(second, first), List.of()));

        var initial = service.recommend();
        var replay = service.recommend();
        var reordered =
                recommend(
                        CLOCK, snapshot(List.of(first, second), List.of()));

        assertThat(initial).isEqualTo(replay).isEqualTo(reordered);
        assertThat(initial.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-a");
    }

    @Test
    void sameDeadlineUsesPriorityBeforeStableIdentityTieBreak() {
        var low =
                task(
                        "task-a",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.LOW,
                        true,
                        false);
        var high =
                task(
                        "task-z",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.HIGH,
                        true,
                        false);

        var result = recommend(CLOCK, snapshot(List.of(low, high), List.of()));

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-z");
        assertThat(result.next()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-a");
    }

    @Test
    void agendaSnapshotCopiesCallerOwnedLists() {
        var tasks = new java.util.ArrayList<ExecutionTaskItem>();
        tasks.add(
                task(
                        "task-1",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false));
        var snapshot = snapshot(tasks, List.of());
        tasks.clear();

        var result = recommend(CLOCK, snapshot);

        assertThat(result.now()).get().extracting(ExecutionRecommendationItem::stableId)
                .isEqualTo("task-1");
        assertThatThrownBy(() -> snapshot.tasks().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void duplicateStableIdentityFailsClosedAcrossTaskAndCalendar() {
        var task =
                task(
                        "same-id",
                        NOW.plus(Duration.ofHours(1)),
                        ExecutionPriority.NORMAL,
                        true,
                        false);
        var event =
                calendar(
                        "same-id",
                        NOW.plus(Duration.ofHours(2)),
                        false,
                        true,
                        true,
                        Duration.ofMinutes(15));

        assertThatThrownBy(
                        () ->
                                recommend(
                                        CLOCK,
                                        snapshot(List.of(task), List.of(event))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stable identity");
    }

    @Test
    void recordsRejectInvalidTimeAndBufferInputs() {
        assertThatThrownBy(
                        () ->
                                new ExecutionCalendarItem(
                                        "calendar-1",
                                        "Calendar",
                                        NOW,
                                        NOW,
                                        false,
                                        true,
                                        true,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ExecutionCalendarItem(
                                        "calendar-1",
                                        "Calendar",
                                        NOW,
                                        NOW.plusSeconds(1),
                                        false,
                                        true,
                                        true,
                                        null,
                                        Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ExecutionQueue recommend(
            Clock clock, ExecutionAgendaSnapshot snapshot) {
        return new ExecutionRecommendationService(clock, () -> snapshot).recommend();
    }

    private static ExecutionAgendaSnapshot snapshot(
            List<ExecutionTaskItem> tasks, List<ExecutionCalendarItem> calendar) {
        return new ExecutionAgendaSnapshot(tasks, calendar);
    }

    private static ExecutionTaskItem task(
            String stableId,
            Instant dueAt,
            ExecutionPriority priority,
            boolean open,
            boolean dependencyBlocked) {
        return new ExecutionTaskItem(
                stableId,
                "Task " + stableId,
                dueAt,
                priority,
                open,
                dependencyBlocked);
    }

    private static ExecutionCalendarItem calendar(
            String stableId,
            Instant startsAt,
            boolean allDay,
            boolean fixed,
            boolean actorAdopted,
            Duration prepBuffer) {
        return new ExecutionCalendarItem(
                stableId,
                "Calendar " + stableId,
                startsAt,
                startsAt.plus(Duration.ofHours(1)),
                allDay,
                fixed,
                actorAdopted,
                null,
                prepBuffer);
    }
}
