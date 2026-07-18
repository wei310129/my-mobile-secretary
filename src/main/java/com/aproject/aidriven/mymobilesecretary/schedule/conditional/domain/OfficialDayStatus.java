package com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain;

import java.time.Instant;
import java.time.LocalDate;

/** A source-attributed official fact. UNKNOWN is a first-class result and never means false. */
public record OfficialDayStatus(
        Fact fact,
        LocalDate date,
        Verdict verdict,
        String sourceName,
        Instant observedAt) {

    public enum Fact {
        NATIONAL_HOLIDAY,
        TYPHOON_WORK_SCHOOL_CLOSURE
    }

    public enum Verdict {
        CONFIRMED_TRUE,
        CONFIRMED_FALSE,
        UNKNOWN
    }

    public OfficialDayStatus {
        if (fact == null || date == null || verdict == null) {
            throw new IllegalArgumentException("official day status is incomplete");
        }
        if (verdict != Verdict.UNKNOWN
                && (sourceName == null || sourceName.isBlank() || observedAt == null)) {
            throw new IllegalArgumentException("confirmed official status requires source evidence");
        }
    }

    public static OfficialDayStatus unknown(Fact fact, LocalDate date) {
        return new OfficialDayStatus(fact, date, Verdict.UNKNOWN, null, null);
    }
}
