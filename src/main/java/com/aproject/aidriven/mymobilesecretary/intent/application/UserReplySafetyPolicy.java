package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;

/** Final guard that keeps implementation diagnostics out of user-facing replies. */
final class UserReplySafetyPolicy {

    private static final List<String> INTERNAL_MARKERS = List.of(
            "lastScheduleId=",
            "lastTaskId=",
            "Java 驗證原因",
            "AI 回覆資料",
            "無法對應到任何能力類型",
            "需由系統回覆",
            "使用者詢問「",
            "使用者確認是",
            "使用者要求「",
            "Intent",
            "intent=",
            "validation",
            "handler",
            "schema",
            "reason=",
            "field=",
            "startAt",
            "endAt",
            "arriveBy",
            "departAt",
            "referenceTitle",
            "nodeId",
            "planId",
            "workspaceId",
            "actorId",
            "sourceCreatedByUserId",
            "UUID");

    private UserReplySafetyPolicy() {
    }

    static String sanitize(String message) {
        if (message == null || message.isBlank()) return message;
        if (INTERNAL_MARKERS.stream().noneMatch(message::contains)) return message;
        return "我剛才的說明不夠清楚，沒有據此建立或修改資料。你要處理哪一筆？"
                + "請直接說名稱或引用那筆資料，我會接著處理，不需要你理解系統內部細節。";
    }
}
