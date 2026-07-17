package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Adds or subtracts a non-zero delta from an item's current inventory. */
public record AdjustInventoryPayload(
        @NotBlank @Size(max = 200) String itemName,
        @NotNull Integer delta) {
}
