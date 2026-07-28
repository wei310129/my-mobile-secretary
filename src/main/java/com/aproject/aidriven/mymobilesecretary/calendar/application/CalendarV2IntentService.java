package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryFilter;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryItem;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryPage;
import com.aproject.aidriven.mymobilesecretary.calendar.query.CalendarQueryService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CalendarV2IntentService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd");

    private final CalendarApplicationService calendar;
    private final CalendarQueryService queries;
    private final Clock clock;

    public CalendarV2IntentService(
            CalendarApplicationService calendar, CalendarQueryService queries, Clock clock) {
        this.calendar = calendar;
        this.queries = queries;
        this.clock = clock;
    }

    public IntentResult create(IntentCommand command) {
        require(command.title(), "title");
        Instant start = parse(command.startAt());
        if (start == null) {
            throw new IllegalArgumentException("schedule missing startAt");
        }
        Instant end = parse(command.endAt());
        if (end == null) {
            return IntentResult.clarificationNeeded(
                    "請告訴我行程的結束時間或預計多久，我會接著建立。");
        }
        CalendarPlanView created = calendar.createPlan(new CreateCalendarPlanCommand(
                "intent-create:" + RequestCorrelationContext.currentId(),
                command.title(),
                CalendarPlacement.interval(start, end, TAIPEI),
                command.safeOptions().category(),
                null,
                null,
                List.of(),
                List.of()));
        return IntentResult.message(
                IntentResult.Action.SCHEDULE_CONFIRMED,
                "已建立行程「%s」，時間是 %s–%s%s。"
                        .formatted(
                                created.title(),
                                format(start),
                                ZonedDateTime.ofInstant(end, TAIPEI)
                                        .format(DateTimeFormatter.ofPattern("HH:mm")),
                                created.category() == null
                                        ? ""
                                        : "，分類為「" + created.category() + "」"));
    }

    public IntentResult list(IntentCommand command) {
        CalendarQueryFilter filter = listFilter(command);
        return listed(queries.query(filter));
    }

    public IntentResult listDate(LocalDate date) {
        Instant from = date.atStartOfDay(TAIPEI).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(TAIPEI).toInstant();
        return listed(queries.query(CalendarQueryFilter.range(from, to, TAIPEI, 20, 0)));
    }

    public IntentResult findOne(IntentCommand command) {
        require(command.title(), "title");
        CalendarQueryPage page = queries.query(new CalendarQueryFilter(
                parse(command.startAt()),
                parse(command.endAt()),
                TAIPEI,
                command.title(),
                command.safeOptions().category(),
                3,
                0));
        if (page.items().isEmpty()) {
            return IntentResult.clarificationNeeded(
                    "找不到符合「%s」的行程，請換個名稱或補上日期。".formatted(command.title()));
        }
        if (page.items().size() > 1) {
            String candidates = page.items().stream()
                    .map(CalendarQueryItem::title)
                    .distinct()
                    .limit(3)
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
            return IntentResult.clarificationNeeded(
                    "找到多筆相近行程：%s。請補上日期或更完整的名稱。".formatted(candidates));
        }
        CalendarQueryItem item = page.items().getFirst();
        return IntentResult.message(
                IntentResult.Action.SCHEDULE_INFO,
                "行程「%s」｜%s%s%s"
                        .formatted(
                                item.title(),
                                format(item.placement()),
                                item.category() == null ? "" : "｜" + item.category(),
                                item.onlineLinkHost() == null
                                        ? ""
                                        : "｜線上：" + item.onlineLinkHost()));
    }

    private CalendarQueryFilter listFilter(IntentCommand command) {
        Instant from = parse(command.startAt());
        Instant to = parse(command.endAt());
        if (command.type() == IntentCommand.Type.LIST_SCHEDULES_ON_DATE) {
            if (from == null) {
                throw new IllegalArgumentException("daily schedule query missing startAt");
            }
            LocalDate day = LocalDate.ofInstant(from, TAIPEI);
            from = day.atStartOfDay(TAIPEI).toInstant();
            to = day.plusDays(1).atStartOfDay(TAIPEI).toInstant();
        } else if (from == null && to == null) {
            from = Instant.now(clock);
            to = from.plusSeconds(90L * 24 * 60 * 60);
        } else if (from == null || to == null) {
            throw new IllegalArgumentException("calendar list range requires startAt and endAt");
        }
        return new CalendarQueryFilter(
                from,
                to,
                TAIPEI,
                command.safeOptions().filter(),
                command.safeOptions().category(),
                20,
                0);
    }

    private static IntentResult listed(CalendarQueryPage page) {
        if (page.items().isEmpty()) {
            return IntentResult.message(
                    IntentResult.Action.SCHEDULES_LISTED, "指定範圍內沒有行程。");
        }
        String lines = page.items().stream()
                .map(item -> "• %s｜%s%s"
                        .formatted(
                                item.title(),
                                format(item.placement()),
                                item.category() == null ? "" : "｜" + item.category()))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        String more = page.nextOffset() == null ? "" : "\n還有其他結果，請縮小日期或關鍵字。";
        return IntentResult.message(
                IntentResult.Action.SCHEDULES_LISTED, "行程：\n" + lines + more);
    }

    private static String format(CalendarPlacement placement) {
        if (placement instanceof CalendarPlacement.TimedInterval interval) {
            return "%s–%s"
                    .formatted(
                            format(interval.start()),
                            ZonedDateTime.ofInstant(interval.end(), interval.zoneId())
                                    .format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        if (placement instanceof CalendarPlacement.TimedPoint point) {
            return ZonedDateTime.ofInstant(point.time(), point.zoneId()).format(DATE_TIME);
        }
        CalendarPlacement.AllDay allDay = (CalendarPlacement.AllDay) placement;
        if (allDay.endExclusive().equals(allDay.start().plusDays(1))) {
            return allDay.start().format(DATE) + " 全天";
        }
        return "%s–%s 全天"
                .formatted(
                        allDay.start().format(DATE),
                        allDay.endExclusive().minusDays(1).format(DATE));
    }

    private static String format(Instant instant) {
        return ZonedDateTime.ofInstant(instant, TAIPEI).format(DATE_TIME);
    }

    private static Instant parse(String value) {
        return value == null || value.isBlank() ? null : ZonedDateTime.parse(value).toInstant();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing " + field);
        }
    }
}
