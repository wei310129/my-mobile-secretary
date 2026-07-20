package com.aproject.aidriven.mymobilesecretary.safety.application;

import java.time.LocalDate;

/** Read-only official-source boundary; it never mutates schedules or user data. */
public interface SuspensionOfficialClient {
    Verification verify(LocalDate noticeDate, String extractedSummary);

    record Verification(boolean reachable, boolean officialMatch,
                        String summary, String sourceUrl) {
    }
}
