package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;

/** 提供給語意解析器的短期上下文;只含可安全指代的摘要。 */
public record ConversationSnapshot(
        Long lastTaskId,
        Long lastScheduleId,
        Long lastPlaceId,
        List<Long> lastTaskListIds,
        List<Long> lastScheduleListIds,
        String lastAction,
        String lastUserText,
        String lastAssistantText
) {
    public static ConversationSnapshot empty() {
        return new ConversationSnapshot(null, null, null, List.of(), List.of(),
                null, null, null);
    }
}
