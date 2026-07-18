package com.aproject.aidriven.mymobilesecretary.schedule.conditional.application;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.OfficialDayStatus;
import java.time.LocalDate;

/** Boundary for a verified government calendar or closure announcement provider. */
public interface OfficialCalendarGateway {

    OfficialDayStatus query(
            OfficialDayStatus.Fact fact, LocalDate date, String jurisdiction);
}
