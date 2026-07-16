package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Optional;

/** 回答「剛才為什麼失敗」，避免再把這類追問丟給 LLM 猜。 */
final class FailureExplanationService {

    private static final String REASON_MARKER = "Java 驗證原因：";
    private static final String COMMAND_MARKER = "AI 回覆資料：";

    private FailureExplanationService() {
    }

    static Optional<IntentResult> answer(String text, ConversationSnapshot snapshot) {
        if (!isFailureQuestion(text)) {
            return Optional.empty();
        }
        if (snapshot == null || snapshot.lastAction() == null
                || !(snapshot.lastAction().equals(IntentResult.Action.AI_UNAVAILABLE.name())
                || snapshot.lastAction().equals(IntentResult.Action.FALLBACK_TASK_CREATED.name()))) {
            return Optional.of(IntentResult.message(IntentResult.Action.FAILURE_EXPLAINED,
                    "目前沒有可追查的上一筆解析失敗紀錄。"));
        }

        String previous = snapshot.lastAssistantText();
        String reason = extract(previous, REASON_MARKER).orElse(null);
        String command = extract(previous, COMMAND_MARKER).orElse(null);
        if (reason == null && command == null) {
            return Optional.of(IntentResult.message(IntentResult.Action.FAILURE_EXPLAINED,
                    "上一筆是舊版留下的失敗紀錄；當時尚未保存 Java 驗證原因與 AI 結構化欄位，所以無法事後還原。"));
        }

        StringBuilder message = new StringBuilder("剛才的 AI 回覆沒有通過 Java 驗證。");
        if (reason != null) {
            message.append("\n- 原因：").append(reason);
        }
        if (command != null) {
            message.append("\n- AI 回覆：").append(command);
        }
        message.append("\n- 結果：Java 沒有執行這筆操作，也沒有異動資料");
        return Optional.of(IntentResult.message(IntentResult.Action.FAILURE_EXPLAINED,
                message.toString()));
    }

    static boolean isFailureQuestion(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("為什麼失敗") || normalized.contains("為何失敗")
                || normalized.contains("為什麼沒成功") || normalized.contains("為何沒成功")
                || normalized.contains("剛才怎麼了") || normalized.contains("剛剛怎麼了")
                || normalized.contains("剛才為什麼") || normalized.contains("剛剛為什麼")
                || normalized.contains("哪裡沒通過") || normalized.contains("哪裡驗證失敗");
    }

    private static Optional<String> extract(String text, String marker) {
        if (text == null) {
            return Optional.empty();
        }
        for (String line : text.split("\\R")) {
            int markerIndex = line.indexOf(marker);
            if (markerIndex >= 0) {
                String value = line.substring(markerIndex + marker.length()).strip();
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }
}
