package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Final guard that keeps implementation diagnostics out of user-facing replies. */
final class UserReplySafetyPolicy {

    private static final List<String> INTERNAL_MARKERS = List.of(
            "lastscheduleid=",
            "lasttaskid=",
            "java 驗證原因",
            "ai 回覆資料",
            "無法對應到任何能力類型",
            "需由系統回覆",
            "使用者詢問「",
            "使用者確認是",
            "使用者要求「",
            "intent",
            "intent=",
            "validation",
            "handler",
            "schema",
            "reason=",
            "field=",
            "startat",
            "endat",
            "arriveby",
            "departat",
            "referencetitle",
            "nodeid",
            "planid",
            "workspaceid",
            "actorid",
            "sourcecreatedbyuserid",
            "uuid",
            "router confidence",
            "provider error",
            "stack trace",
            "exception");
    private static final Pattern UUID_VALUE = Pattern.compile(
            "(?i).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*");

    private UserReplySafetyPolicy() {
    }

    static String sanitize(String message) {
        if (message == null || message.isBlank()) return message;
        String lower = message.toLowerCase(Locale.ROOT);
        if (INTERNAL_MARKERS.stream().noneMatch(lower::contains)
                && !UUID_VALUE.matcher(message).matches()) {
            return message;
        }
        return "我剛才的說明不夠清楚，沒有據此建立或修改資料。您要處理哪一筆？"
                + "請直接說名稱或引用那筆資料；在確認目標前不會變更資料，"
                + "也不需要您理解系統內部細節。";
    }
}
