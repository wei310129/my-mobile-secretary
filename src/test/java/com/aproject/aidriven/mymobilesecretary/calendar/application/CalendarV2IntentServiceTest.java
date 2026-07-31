package com.aproject.aidriven.mymobilesecretary.calendar.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlanStatus;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryFilter;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryItem;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryPage;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryService;
import com.aproject.aidriven.mymobilesecretary.calendar.recurrence.CalendarRecurrenceRegistrationService;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceAliasService;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CalendarV2IntentServiceTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private CalendarApplicationService calendar;
    private CalendarQueryService queries;
    private PlaceAliasService places;
    private CalendarRecurrenceRegistrationService recurrences;
    private CalendarV2IntentService service;

    @BeforeEach
    void setUp() {
        calendar = mock(CalendarApplicationService.class);
        queries = mock(CalendarQueryService.class);
        places = mock(PlaceAliasService.class);
        recurrences = mock(CalendarRecurrenceRegistrationService.class);
        service = new CalendarV2IntentService(
                calendar,
                queries,
                places,
                recurrences,
                Clock.fixed(Instant.parse("2026-07-24T04:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void completeCreateUsesTypedIntervalCategoryAndCorrelationIdempotencyKey() {
        UUID requestId = UUID.randomUUID();
        IntentCommand command = command(
                IntentCommand.Type.CREATE_SCHEDULE,
                "客戶會議",
                "2026-07-29T09:00:00+08:00",
                "2026-07-29T10:00:00+08:00",
                IntentOptions.empty().withCategory("工作"));
        when(calendar.createPlanWithIdentity(any())).thenReturn(created("客戶會議", "工作"));

        IntentResult result =
                RequestCorrelationContext.run(requestId, () -> service.create(command));

        ArgumentCaptor<CreateCalendarPlanCommand> captured =
                ArgumentCaptor.forClass(CreateCalendarPlanCommand.class);
        verify(calendar).createPlanWithIdentity(captured.capture());
        assertThat(captured.getValue().requestKey())
                .isEqualTo("intent-create:" + requestId);
        assertThat(captured.getValue().placement())
                .isEqualTo(CalendarPlacement.interval(
                        Instant.parse("2026-07-29T01:00:00Z"),
                        Instant.parse("2026-07-29T02:00:00Z"),
                        TAIPEI));
        assertThat(captured.getValue().category()).isEqualTo("工作");
        assertThat(captured.getValue().nodes()).singleElement()
                .satisfies(node -> {
                    assertThat(node.node().id()).isEqualTo("start");
                    assertThat(node.node().absoluteTime())
                            .isEqualTo(Instant.parse("2026-07-29T01:00:00Z"));
                });
        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
        assertThat(result.message()).contains("客戶會議", "工作").doesNotContain(requestId.toString());
    }

    @Test
    void omittedEndAsksForClarificationWithZeroMutation() {
        IntentResult result = service.create(command(
                IntentCommand.Type.CREATE_SCHEDULE,
                "跟朋友吃飯",
                "2026-07-25T19:00:00+08:00",
                null,
                IntentOptions.empty()));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("結束時間");
        verifyNoInteractions(calendar, queries);
    }

    @Test
    void startOnlyUserEvidenceCannotBecomeAModelInventedInterval() {
        UUID requestId = UUID.randomUUID();
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "領取包裹",
                null,
                "2026-07-25T11:00:00+08:00",
                "2026-07-25T12:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                "週六上午十一點領取包裹");
        when(calendar.createPlanWithIdentity(any())).thenReturn(created("領取包裹", null));

        IntentResult result =
                RequestCorrelationContext.run(requestId, () -> service.create(command));

        ArgumentCaptor<CreateCalendarPlanCommand> captured =
                ArgumentCaptor.forClass(CreateCalendarPlanCommand.class);
        verify(calendar).createPlanWithIdentity(captured.capture());
        assertThat(captured.getValue().placement())
                .isEqualTo(CalendarPlacement.point(
                        Instant.parse("2026-07-25T03:00:00Z"), TAIPEI));
        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_CONFIRMED);
        assertThat(result.message()).contains("領取包裹", "07/25 11:00")
                .doesNotContain("12:00");
    }

    @Test
    void explicitDurationWinsOverADifferentModelEnd() {
        UUID requestId = UUID.randomUUID();
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "專案討論",
                null,
                "2026-07-25T14:00:00+08:00",
                "2026-07-25T15:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                "週六下午兩點進行四十五分鐘的專案討論");
        when(calendar.createPlanWithIdentity(any())).thenReturn(created("專案討論", null));

        RequestCorrelationContext.run(requestId, () -> service.create(command));

        ArgumentCaptor<CreateCalendarPlanCommand> captured =
                ArgumentCaptor.forClass(CreateCalendarPlanCommand.class);
        verify(calendar).createPlanWithIdentity(captured.capture());
        assertThat(captured.getValue().placement())
                .isEqualTo(CalendarPlacement.interval(
                        Instant.parse("2026-07-25T06:00:00Z"),
                        Instant.parse("2026-07-25T06:45:00Z"),
                        TAIPEI));
    }

    @Test
    void recurringCalendarCreateRegistersTheSeriesInTheSameRequest() {
        UUID requestId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        CalendarPlacement placement = CalendarPlacement.interval(
                Instant.parse("2026-07-25T06:00:00Z"),
                Instant.parse("2026-07-25T08:00:00Z"),
                TAIPEI);
        when(calendar.createPlanWithIdentity(any())).thenReturn(new CalendarPlanIdentityView(
                planId, "上陶藝課", "學習", null, CalendarPlanStatus.ACTIVE, 1));
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "上陶藝課",
                null,
                "2026-07-25T14:00:00+08:00",
                "2026-07-25T16:00:00+08:00",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true,
                IntentOptions.empty(),
                "每週六下午兩點到四點上陶藝課");

        RequestCorrelationContext.run(requestId, () -> service.create(command));

        verify(recurrences).registerPlan(
                planId,
                placement,
                "WEEKLY",
                null,
                "intent-create:" + requestId + ":recurrence");
    }

    @Test
    void knownPlaceIsPersistedOnTheStartNodeWithoutNameOnlyLookup() {
        Place place = Place.create(
                "社區教室",
                "新北市測試區安全路88號",
                24.95,
                121.54,
                "教室",
                Instant.parse("2026-07-24T04:00:00Z"));
        when(places.resolve("社區教室")).thenReturn(Optional.of(place));
        when(calendar.createPlanWithIdentity(any())).thenReturn(created("上陶藝課", null));
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "上陶藝課",
                null,
                "2026-07-25T14:00:00+08:00",
                "2026-07-25T16:00:00+08:00",
                "社區教室",
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                "週六下午兩點到四點在社區教室上陶藝課");

        RequestCorrelationContext.run(UUID.randomUUID(), () -> service.create(command));

        ArgumentCaptor<CreateCalendarPlanCommand> captured =
                ArgumentCaptor.forClass(CreateCalendarPlanCommand.class);
        verify(calendar).createPlanWithIdentity(captured.capture());
        assertThat(captured.getValue().nodes()).singleElement()
                .satisfies(node -> {
                    assertThat(node.location().label()).isEqualTo("社區教室");
                    assertThat(node.location().latitude()).isEqualTo(24.95);
                    assertThat(node.location().longitude()).isEqualTo(121.54);
                });
    }

    @Test
    void unknownPlaceFailsClosedBeforeCreatingACalendarPlan() {
        when(places.resolve("未確認教室")).thenReturn(Optional.empty());
        IntentCommand command = new IntentCommand(
                IntentCommand.Type.CREATE_SCHEDULE,
                "上陶藝課",
                null,
                "2026-07-25T14:00:00+08:00",
                "2026-07-25T16:00:00+08:00",
                "未確認教室",
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                IntentOptions.empty(),
                "週六下午兩點到四點在未確認教室上陶藝課");

        IntentResult result = service.create(command);

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("未確認教室", "先建立或確認地點");
        verifyNoInteractions(calendar, queries);
    }

    @Test
    void dailyListBuildsTaipeiHalfOpenDayRange() {
        when(queries.query(any())).thenReturn(new CalendarQueryPage(List.of(), null));
        IntentCommand command = command(
                IntentCommand.Type.LIST_SCHEDULES_ON_DATE,
                null,
                "2026-07-25T00:00:00+08:00",
                null,
                IntentOptions.empty());

        service.list(command);

        ArgumentCaptor<CalendarQueryFilter> captured =
                ArgumentCaptor.forClass(CalendarQueryFilter.class);
        verify(queries).query(captured.capture());
        assertThat(captured.getValue().fromInclusive())
                .isEqualTo(Instant.parse("2026-07-24T16:00:00Z"));
        assertThat(captured.getValue().toExclusive())
                .isEqualTo(Instant.parse("2026-07-25T16:00:00Z"));
    }

    @Test
    void boundedListCarriesKeywordAndCategoryAsTypedFilters() {
        when(queries.query(any())).thenReturn(new CalendarQueryPage(List.of(), null));
        IntentCommand command = command(
                IntentCommand.Type.LIST_SCHEDULES,
                null,
                "2026-07-27T00:00:00+08:00",
                "2026-08-03T00:00:00+08:00",
                IntentOptions.empty().withFilter("電影").withCategory("家庭"));

        service.list(command);

        ArgumentCaptor<CalendarQueryFilter> captured =
                ArgumentCaptor.forClass(CalendarQueryFilter.class);
        verify(queries).query(captured.capture());
        assertThat(captured.getValue().keyword()).isEqualTo("電影");
        assertThat(captured.getValue().category()).isEqualTo("家庭");
        assertThat(captured.getValue().limit()).isEqualTo(20);
    }

    @Test
    void partialListRangeFailsClosedInsteadOfGuessing() {
        IntentCommand command = command(
                IntentCommand.Type.LIST_SCHEDULES,
                null,
                "2026-07-27T00:00:00+08:00",
                null,
                IntentOptions.empty());

        assertThatThrownBy(() -> service.list(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires startAt and endAt");
        verifyNoInteractions(calendar, queries);
    }

    @Test
    void ambiguousNamedSearchAsksForMoreDetailAndDoesNotMutate() {
        when(queries.query(any())).thenReturn(new CalendarQueryPage(
                List.of(
                        item("客戶會議", "2026-07-25T01:00:00Z"),
                        item("產品會議", "2026-07-26T01:00:00Z")),
                null));

        IntentResult result = service.findOne(command(
                IntentCommand.Type.ASK_SCHEDULE_INFO,
                "會議",
                null,
                null,
                IntentOptions.empty()));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("客戶會議", "產品會議", "日期");
        verifyNoInteractions(calendar);
    }

    @Test
    void uniqueNamedSearchReturnsOnlySafeCalendarFields() {
        when(queries.query(any())).thenReturn(new CalendarQueryPage(
                List.of(new CalendarQueryItem(
                        "線上讀書會",
                        CalendarPlacement.allDay(
                                LocalDate.parse("2026-07-26"),
                                LocalDate.parse("2026-07-27")),
                        "學習",
                        "meet.example.com")),
                null));

        IntentResult result = service.findOne(command(
                IntentCommand.Type.ASK_SCHEDULE_INFO,
                "線上讀書會",
                null,
                null,
                IntentOptions.empty()));

        assertThat(result.action()).isEqualTo(IntentResult.Action.SCHEDULE_INFO);
        assertThat(result.message())
                .contains("線上讀書會", "07/26 全天", "學習", "meet.example.com")
                .doesNotContain("http", "UUID", "workspace");
    }

    @Test
    void emptySearchExplainsNoMatchWithoutMutation() {
        when(queries.query(any())).thenReturn(new CalendarQueryPage(List.of(), null));

        IntentResult result = service.findOne(command(
                IntentCommand.Type.ASK_SCHEDULE_INFO,
                "不存在的活動",
                null,
                null,
                IntentOptions.empty()));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("找不到", "不存在的活動");
        verifyNoInteractions(calendar);
    }

    private static CalendarQueryItem item(String title, String start) {
        return new CalendarQueryItem(
                title,
                CalendarPlacement.point(Instant.parse(start), TAIPEI),
                null,
                null);
    }

    private static CalendarPlanIdentityView created(String title, String category) {
        return new CalendarPlanIdentityView(
                UUID.randomUUID(), title, category, null, CalendarPlanStatus.ACTIVE, 1);
    }

    private static IntentCommand command(
            IntentCommand.Type type,
            String title,
            String startAt,
            String endAt,
            IntentOptions options) {
        return new IntentCommand(
                type,
                title,
                null,
                startAt,
                endAt,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                options);
    }
}
