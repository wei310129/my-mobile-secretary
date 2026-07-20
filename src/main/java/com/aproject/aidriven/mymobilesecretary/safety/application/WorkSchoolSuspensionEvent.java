package com.aproject.aidriven.mymobilesecretary.safety.application;

import java.time.Instant;
import java.time.LocalDate;

/** Audit-safe lifecycle event; summary content remains in the actor-isolated draft. */
public record WorkSchoolSuspensionEvent(LocalDate noticeDate, Action action, Instant occurredAt) {
    public enum Action { CAPTURED, DECLINED, OFFICIAL_CONFIRMED, OFFICIAL_NOT_CONFIRMED, VERIFICATION_FAILED }
}
