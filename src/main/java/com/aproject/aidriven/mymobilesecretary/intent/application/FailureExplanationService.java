package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.Optional;

/** 回答「剛才為什麼失敗」，避免再把這類追問丟給 LLM 猜。 */
final class FailureExplanationService {

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
        String message;
        if (previous != null && previous.contains("開始時間")) {
            message = "剛才沒有完成，因為還缺行程的開始時間；資料沒有異動。"
                    + "您補上開始時間後，可以再送出這項要求重新驗證。";
        } else if (previous != null && previous.contains("結束時間")) {
            message = "剛才沒有完成，因為還缺行程的結束時間或預計時長；資料沒有異動。"
                    + "您補上其中一項後，可以再送出這項要求重新驗證。";
        } else {
            message = "剛才沒有完成，資料也沒有異動。請把要處理的項目、日期與時間一起告訴我，"
                    + "資料補齊後即可重新驗證這項要求。";
        }
        return Optional.of(IntentResult.message(IntentResult.Action.FAILURE_EXPLAINED,
                message));
    }

    static boolean isFailureQuestion(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return normalized.contains("為什麼失敗") || normalized.contains("為何失敗")
                || normalized.contains("為什麼沒成功") || normalized.contains("為何沒成功")
                || normalized.contains("剛才怎麼了") || normalized.contains("剛剛怎麼了")
                || normalized.contains("剛才為什麼") || normalized.contains("剛剛為什麼")
                || normalized.contains("哪裡沒通過") || normalized.contains("哪裡驗證失敗");
    }

}
