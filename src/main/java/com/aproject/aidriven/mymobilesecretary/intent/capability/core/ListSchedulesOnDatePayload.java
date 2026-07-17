package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Queries the complete schedule overview for one resolved calendar date. */
public record ListSchedulesOnDatePayload(
        @NotNull LocalDate date) {
}
