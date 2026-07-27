package com.aproject.aidriven.mymobilesecretary.calendar.persistence;

import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
class CalendarPlacementEmbeddable {

    @Enumerated(EnumType.STRING)
    @Column(name = "placement_kind", nullable = false, length = 20)
    private CalendarPlacement.Kind kind;

    @Column(name = "timed_start")
    private Instant timedStart;

    @Column(name = "timed_end")
    private Instant timedEnd;

    @Column(name = "zone_id", length = 64)
    private String zoneId;

    @Column(name = "all_day_start")
    private LocalDate allDayStart;

    @Column(name = "all_day_end_exclusive")
    private LocalDate allDayEndExclusive;

    protected CalendarPlacementEmbeddable() {}

    private CalendarPlacementEmbeddable(CalendarPlacement placement) {
        Objects.requireNonNull(placement, "placement");
        kind = placement.kind();
        switch (placement) {
            case CalendarPlacement.TimedInterval interval -> {
                timedStart = interval.start();
                timedEnd = interval.end();
                zoneId = interval.zoneId().getId();
            }
            case CalendarPlacement.TimedPoint point -> {
                timedStart = point.time();
                zoneId = point.zoneId().getId();
            }
            case CalendarPlacement.AllDay allDay -> {
                allDayStart = allDay.start();
                allDayEndExclusive = allDay.endExclusive();
            }
        }
    }

    static CalendarPlacementEmbeddable from(CalendarPlacement placement) {
        return new CalendarPlacementEmbeddable(placement);
    }

    CalendarPlacement toDomain() {
        return switch (kind) {
            case TIMED_INTERVAL ->
                CalendarPlacement.interval(timedStart, timedEnd, ZoneId.of(zoneId));
            case TIMED_POINT -> CalendarPlacement.point(timedStart, ZoneId.of(zoneId));
            case ALL_DAY -> CalendarPlacement.allDay(allDayStart, allDayEndExclusive);
        };
    }
}
