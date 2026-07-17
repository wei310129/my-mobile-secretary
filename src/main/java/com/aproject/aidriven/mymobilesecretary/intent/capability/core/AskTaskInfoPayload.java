package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Selects one task by a spoken title or a prior-list ordinal. */
public record AskTaskInfoPayload(
        @Size(max = 200) String title,
        @Positive Integer ordinal) {
}
