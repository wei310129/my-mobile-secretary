package com.aproject.aidriven.mymobilesecretary.api.item;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * 登錄品項知識的請求。
 *
 * @param placeIds 可購買地點(至少一個,必須都存在)
 */
public record CreateItemRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty Set<Long> placeIds
) {
}
