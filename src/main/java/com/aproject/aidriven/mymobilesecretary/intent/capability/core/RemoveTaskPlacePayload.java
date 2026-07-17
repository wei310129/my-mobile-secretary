package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Removes one task-place reminder binding without canceling the task itself. */
public record RemoveTaskPlacePayload(
        @Size(max = 200) String taskTitle,
        @Positive Integer ordinal,
        @NotBlank @Size(max = 200) String placeName) {
}
