package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

public sealed interface CalendarPlacement
        permits CalendarPlacement.TimedInterval, CalendarPlacement.TimedPoint, CalendarPlacement.AllDay {

    enum Kind {
        TIMED_INTERVAL,
        TIMED_POINT,
        ALL_DAY
    }

    Kind kind();

    static TimedInterval interval(Instant start, Instant end, ZoneId zoneId) {
        return new TimedInterval(start, end, zoneId);
    }

    static TimedPoint point(Instant time, ZoneId zoneId) {
        return new TimedPoint(time, zoneId);
    }

    static AllDay allDay(LocalDate start, LocalDate endExclusive) {
        return new AllDay(start, endExclusive);
    }

    record TimedInterval(Instant start, Instant end, ZoneId zoneId) implements CalendarPlacement {
        public TimedInterval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(zoneId, "zoneId");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("Interval end must be after start");
            }
        }

        @Override
        public Kind kind() {
            return Kind.TIMED_INTERVAL;
        }
    }

    record TimedPoint(Instant time, ZoneId zoneId) implements CalendarPlacement {
        public TimedPoint {
            Objects.requireNonNull(time, "time");
            Objects.requireNonNull(zoneId, "zoneId");
        }

        @Override
        public Kind kind() {
            return Kind.TIMED_POINT;
        }
    }

    record AllDay(LocalDate start, LocalDate endExclusive) implements CalendarPlacement {
        public AllDay {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(endExclusive, "endExclusive");
            if (!endExclusive.isAfter(start)) {
                throw new IllegalArgumentException("All-day end must be after start");
            }
        }

        @Override
        public Kind kind() {
            return Kind.ALL_DAY;
        }
    }
}
