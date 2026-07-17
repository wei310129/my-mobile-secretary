package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Sets an item's absolute inventory quantity. */
public record SetInventoryPayload(
        @NotBlank @Size(max = 200) String itemName,
        @NotNull @PositiveOrZero Integer quantity) {
}
