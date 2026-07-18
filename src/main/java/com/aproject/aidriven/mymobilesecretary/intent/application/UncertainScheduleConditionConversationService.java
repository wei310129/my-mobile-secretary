package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 未來是否發生仍未知的條件，不得預先建立互斥的兩份行程。 */
@Service
public class UncertainScheduleConditionConversationService {

    public Optional<IntentResult> answer(String text) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (looksLikeMeta(compact) || !isUnknownOverrunCondition(compact)) {
            return Optional.empty();
        }
        return Optional.of(IntentResult.clarificationNeeded(
                "目前還不知道前一場是否會拖延，所以我不會同時建立原定與延後兩個互斥版本，"
                        + "也不會先猜成一定延後。是否先保留原定時段，並在前一場快結束時提醒你確認；"
                        + "若確定拖延，再把同一筆行程延後半小時？"));
    }

    private static boolean isUnknownOverrunCondition(String text) {
        boolean overrun = text.contains("前一場")
                && (text.contains("拖到") || text.contains("拖延") || text.contains("延誤"));
        boolean branches = (text.contains("如果") || text.contains("若"))
                && (text.contains("沒拖到") || text.contains("沒有拖") || text.contains("否則"))
                && (text.contains("延後") || text.contains("順延"))
                && (text.contains("照原定") || text.contains("原時間") || text.contains("維持"));
        boolean unknown = text.contains("還不知道") || text.contains("不確定")
                || text.contains("現在不知道") || text.contains("尚未確定");
        return overrun && branches && unknown;
    }

    private static boolean looksLikeMeta(String text) {
        return List.of("情境清單", "測試資料", "功能開發", "使用者說").stream()
                .anyMatch(text::contains);
    }
}
