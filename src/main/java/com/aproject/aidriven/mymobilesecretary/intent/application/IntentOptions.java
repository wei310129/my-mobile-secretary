package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;

/**
 * 生活化指令的選填參數。
 * 核心欄位留在 IntentCommand;只有部分能力會用到的值集中在這裡。
 */
public record IntentOptions(
        String filter,
        Integer ordinal,
        Integer durationMinutes,
        Integer leadMinutes,
        Integer radiusMeters,
        String triggerType,
        String recurrence,
        String category,
        List<String> itemNames,
        Integer quantity,
        String referenceTitle,
        String referenceKind,
        String timeOfDay,
        Boolean keepTime,
        Integer shiftMinutes,
        String condition,
        String fromPlaceName,
        Integer bufferMinutes,
        String clarificationQuestion,
        String alias
) {
    public static IntentOptions empty() {
        return new IntentOptions(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
