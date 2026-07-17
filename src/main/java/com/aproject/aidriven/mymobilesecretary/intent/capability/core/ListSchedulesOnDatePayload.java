package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import java.time.LocalDate;
import jakarta.validation.constraints.NotNull;

/** Queries the complete schedule overview for one resolved calendar date. */
public record ListSchedulesOnDatePayload(
        @NotNull LocalDate date) {
}
