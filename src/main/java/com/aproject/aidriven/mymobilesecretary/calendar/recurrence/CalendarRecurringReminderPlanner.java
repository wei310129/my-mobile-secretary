package com.aproject.aidriven.mymobilesecretary.calendar.recurrence;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CalendarRecurringReminderPlanner {

    public static final int MAX_TEMPLATES = 8;

    private CalendarRecurringReminderPlanner() {}

    public static List<Materialization> plan(
            UUID seriesId,
            int recurrenceRuleRevision,
            CalendarRecurrenceRule rule,
            CalendarRecurrenceExceptions exceptions,
            CalendarRecurrenceWindow window,
            List<Template> templates) {
        Objects.requireNonNull(seriesId, "seriesId");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(exceptions, "exceptions");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(templates, "templates");
        if (recurrenceRuleRevision <= 0) {
            throw new IllegalArgumentException("Recurrence rule revision must be positive");
        }
        if (templates.isEmpty() || templates.size() > MAX_TEMPLATES) {
            throw new IllegalArgumentException("A recurrence requires 1 to 8 reminder templates");
        }
        long uniqueTemplates = templates.stream().map(Template::id).distinct().count();
        if (uniqueTemplates != templates.size()) {
            throw new IllegalArgumentException("Reminder template ids must be unique");
        }

        return CalendarRecurrenceExpander.expand(rule, exceptions, window).stream()
                .flatMap(occurrence -> templates.stream().map(template -> {
                    Instant onset = onset(occurrence.placement(), template);
                    var key = new MaterializationKey(
                            template.id(),
                            template.revision(),
                            seriesId,
                            recurrenceRuleRevision,
                            occurrence.key());
                    return new Materialization(key, onset.plus(template.offset()));
                }))
                .sorted(Comparator.comparing(Materialization::scheduledAt)
                        .thenComparing(value -> value.key().templateId()))
                .toList();
    }

    private static Instant onset(CalendarPlacement placement, Template template) {
        return switch (placement) {
            case CalendarPlacement.TimedPoint point -> point.time();
            case CalendarPlacement.TimedInterval interval -> interval.start();
            case CalendarPlacement.AllDay allDay -> allDay.start()
                    .atTime(template.allDayTime())
                    .atZone(template.zoneId())
                    .toInstant();
        };
    }

    public record Template(
            UUID id, long revision, Duration offset, ZoneId zoneId, LocalTime allDayTime) {
        public Template {
            Objects.requireNonNull(id, "id");
            if (revision <= 0) {
                throw new IllegalArgumentException("Reminder template revision must be positive");
            }
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(zoneId, "zoneId");
            Objects.requireNonNull(allDayTime, "allDayTime");
        }

        public static Template timed(UUID id, long revision, Duration offset, ZoneId zoneId) {
            return new Template(id, revision, offset, zoneId, LocalTime.MIDNIGHT);
        }
    }

    public record MaterializationKey(
            UUID templateId,
            long templateRevision,
            UUID seriesId,
            int recurrenceRuleRevision,
            CalendarOccurrenceKey occurrenceKey) {
        public MaterializationKey {
            Objects.requireNonNull(templateId, "templateId");
            Objects.requireNonNull(seriesId, "seriesId");
            Objects.requireNonNull(occurrenceKey, "occurrenceKey");
        }
    }

    public record Materialization(MaterializationKey key, Instant scheduledAt) {
        public Materialization {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(scheduledAt, "scheduledAt");
        }
    }
}
