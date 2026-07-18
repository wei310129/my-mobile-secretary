package com.aproject.aidriven.mymobilesecretary.schedule.conditional.application;

import com.aproject.aidriven.mymobilesecretary.schedule.conditional.domain.OfficialDayStatus;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/** Safe default: without an official connector every fact remains unknown. */
@Component
public class UnavailableOfficialCalendarGateway implements OfficialCalendarGateway {

    @Override
    public OfficialDayStatus query(
            OfficialDayStatus.Fact fact, LocalDate date, String jurisdiction) {
        return OfficialDayStatus.unknown(fact, date);
    }
}
