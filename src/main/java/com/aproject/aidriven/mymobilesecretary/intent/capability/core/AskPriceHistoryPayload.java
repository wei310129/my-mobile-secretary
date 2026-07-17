package com.aproject.aidriven.mymobilesecretary.intent.capability.core;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Selects one item whose recorded purchase prices should be queried. */
public record AskPriceHistoryPayload(
        @NotBlank @Size(max = 200) String itemName) {
}
