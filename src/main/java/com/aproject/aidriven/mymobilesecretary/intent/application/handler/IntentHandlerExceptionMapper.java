package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;

/** Preserves the legacy lifestyle-command clarification contract during handler migration. */
final class IntentHandlerExceptionMapper {

    private IntentHandlerExceptionMapper() {
    }

    static IntentResult clarification(IllegalArgumentException exception) {
        String detail = exception.getMessage();
        if (detail != null && detail.contains("current location")) {
            return clarification("handler.current-location", "currentLocation",
                    "你可以先傳目前位置給我嗎？");
        }
        if (detail != null && detail.contains("unknown destination")) {
            return clarification("handler.destination", "destination",
                    "目的地的完整名稱是什麼？");
        }
        if (detail != null && detail.contains("not unique")) {
            return clarification("handler.target", "target",
                    "你要處理的那一筆完整名稱是什麼？");
        }
        if (detail != null && detail.contains("context")) {
            return clarification("handler.context-target", "target",
                    "你要接著處理的待辦或行程名稱是什麼？");
        }
        return clarification("handler.target-name", "target", "你要處理的名稱是什麼？");
    }

    private static IntentResult clarification(String code, String slot, String prompt) {
        return IntentResult.clarificationNeeded(
                ClarificationStep.blocking(code, slot, prompt, 10));
    }
}
