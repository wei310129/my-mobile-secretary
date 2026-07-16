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
        String alias,
        String newTitle,
        String description,
        String quietStart,
        String quietEnd,
        Boolean allowHighPriority,
        String recurrenceUntil
) {
    /** 相容既有 25 欄呼叫端；新增的固定行程截止日預設為空。 */
    public IntentOptions(String filter, Integer ordinal, Integer durationMinutes,
                         Integer leadMinutes, Integer radiusMeters, String triggerType,
                         String recurrence, String category, List<String> itemNames,
                         Integer quantity, String referenceTitle, String referenceKind,
                         String timeOfDay, Boolean keepTime, Integer shiftMinutes,
                         String condition, String fromPlaceName, Integer bufferMinutes,
                         String clarificationQuestion, String alias, String newTitle,
                         String description, String quietStart, String quietEnd,
                         Boolean allowHighPriority) {
        this(filter, ordinal, durationMinutes, leadMinutes, radiusMeters, triggerType,
                recurrence, category, itemNames, quantity, referenceTitle, referenceKind,
                timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName, bufferMinutes,
                clarificationQuestion, alias, newTitle, description, quietStart, quietEnd,
                allowHighPriority, null);
    }

    /** 既有 22 欄呼叫端相容建構子。 */
    public IntentOptions(String filter, Integer ordinal, Integer durationMinutes,
                         Integer leadMinutes, Integer radiusMeters, String triggerType,
                         String recurrence, String category, List<String> itemNames,
                         Integer quantity, String referenceTitle, String referenceKind,
                         String timeOfDay, Boolean keepTime, Integer shiftMinutes,
                         String condition, String fromPlaceName, Integer bufferMinutes,
                         String clarificationQuestion, String alias, String newTitle,
                         String description) {
        this(filter, ordinal, durationMinutes, leadMinutes, radiusMeters, triggerType,
                recurrence, category, itemNames, quantity, referenceTitle, referenceKind,
                timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName, bufferMinutes,
                clarificationQuestion, alias, newTitle, description, null, null, null, null);
    }

    public static IntentOptions empty() {
        return new IntentOptions(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
