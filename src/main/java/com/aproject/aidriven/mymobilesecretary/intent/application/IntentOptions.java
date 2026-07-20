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
        String recurrenceUntil,
        String recurrenceScope
) {
    /** 相容既有 26 欄呼叫端；新增的固定行程改期範圍預設為空。 */
    public IntentOptions(String filter, Integer ordinal, Integer durationMinutes,
                         Integer leadMinutes, Integer radiusMeters, String triggerType,
                         String recurrence, String category, List<String> itemNames,
                         Integer quantity, String referenceTitle, String referenceKind,
                         String timeOfDay, Boolean keepTime, Integer shiftMinutes,
                         String condition, String fromPlaceName, Integer bufferMinutes,
                         String clarificationQuestion, String alias, String newTitle,
                         String description, String quietStart, String quietEnd,
                         Boolean allowHighPriority, String recurrenceUntil) {
        this(filter, ordinal, durationMinutes, leadMinutes, radiusMeters, triggerType,
                recurrence, category, itemNames, quantity, referenceTitle, referenceKind,
                timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName, bufferMinutes,
                clarificationQuestion, alias, newTitle, description, quietStart, quietEnd,
                allowHighPriority, recurrenceUntil, null);
    }

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
                allowHighPriority, null, null);
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
                clarificationQuestion, alias, newTitle, description, null, null, null, null, null);
    }

    public static IntentOptions empty() {
        return new IntentOptions(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    public IntentOptions withLeadMinutes(Integer value) {
        return new IntentOptions(filter, ordinal, durationMinutes, value, radiusMeters,
                triggerType, recurrence, category, itemNames, quantity, referenceTitle,
                referenceKind, timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName,
                bufferMinutes, clarificationQuestion, alias, newTitle, description,
                quietStart, quietEnd, allowHighPriority, recurrenceUntil, recurrenceScope);
    }

    public IntentOptions afterTaskCompletion(String predecessorTitle, Integer delayMinutes) {
        return new IntentOptions(filter, ordinal, durationMinutes, leadMinutes, radiusMeters,
                triggerType, recurrence, category, itemNames, quantity, predecessorTitle,
                "AFTER_TASK_COMPLETION", timeOfDay, keepTime, delayMinutes, condition,
                fromPlaceName, bufferMinutes, clarificationQuestion, alias, newTitle, description,
                quietStart, quietEnd, allowHighPriority, recurrenceUntil, recurrenceScope);
    }

    public IntentOptions withDepartureOrigin(String origin, Integer arrivalBufferMinutes) {
        return new IntentOptions(filter, ordinal, durationMinutes, leadMinutes, radiusMeters,
                triggerType, recurrence, category, itemNames, quantity, referenceTitle,
                referenceKind, timeOfDay, keepTime, shiftMinutes, condition, origin,
                arrivalBufferMinutes, clarificationQuestion, alias, newTitle, description,
                quietStart, quietEnd, allowHighPriority, recurrenceUntil, recurrenceScope);
    }

    public IntentOptions withHypotheticalBuffers(
            Integer preparationMinutes, Integer afterTravelMinutes) {
        return new IntentOptions(filter, ordinal, durationMinutes, preparationMinutes, radiusMeters,
                triggerType, recurrence, category, itemNames, quantity, referenceTitle,
                referenceKind, timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName,
                afterTravelMinutes, clarificationQuestion, alias, newTitle, description,
                quietStart, quietEnd, allowHighPriority, recurrenceUntil, recurrenceScope);
    }

    public IntentOptions withFilter(String value) {
        return new IntentOptions(value, ordinal, durationMinutes, leadMinutes, radiusMeters,
                triggerType, recurrence, category, itemNames, quantity, referenceTitle,
                referenceKind, timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName,
                bufferMinutes, clarificationQuestion, alias, newTitle, description,
                quietStart, quietEnd, allowHighPriority, recurrenceUntil, recurrenceScope);
    }

    public IntentOptions withCategory(String value) {
        return new IntentOptions(filter, ordinal, durationMinutes, leadMinutes, radiusMeters,
                triggerType, recurrence, value, itemNames, quantity, referenceTitle,
                referenceKind, timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName,
                bufferMinutes, clarificationQuestion, alias, newTitle, description,
                quietStart, quietEnd, allowHighPriority, recurrenceUntil, recurrenceScope);
    }

    public IntentOptions withTagRelation(
            String target, String relation, String sourceKind, String targetKind) {
        return new IntentOptions(targetKind, ordinal, durationMinutes, leadMinutes, radiusMeters,
                triggerType, recurrence, sourceKind, itemNames, quantity, target, relation,
                timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName, bufferMinutes,
                clarificationQuestion, alias, newTitle, description, quietStart, quietEnd,
                allowHighPriority, recurrenceUntil, recurrenceScope);
    }

    public IntentOptions withLifeRecord(String recordType, List<String> tags, String details) {
        return new IntentOptions(filter, ordinal, durationMinutes, leadMinutes, radiusMeters,
                triggerType, recurrence, recordType, tags, quantity, referenceTitle,
                referenceKind, timeOfDay, keepTime, shiftMinutes, condition, fromPlaceName,
                bufferMinutes, clarificationQuestion, alias, newTitle, details, quietStart,
                quietEnd, allowHighPriority, recurrenceUntil, recurrenceScope);
    }
}
