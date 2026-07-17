package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Selects one open task to cancel; target ambiguity is resolved before execution. */
public record CancelTaskPayload(
        @Size(max = 200) String title,
        @Positive Integer ordinal) {
}
