package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.List;
import java.util.Optional;

/**
 * 應景祝賀語(使用者 2026-07-16 裁決 #47 附帶要求):回覆不能死板,
 * 使用者話中帶場合(生日、慶功、紀念日)時要跟著道賀——「不要多但是要有」,
 * 一律只附一句、掛在回覆最後,且回饋/抱怨類不加(在道歉場合慶祝很失禮)。
 */
final class OccasionGreeting {

    /** 場合線索 → 祝賀語;順序即優先序,只取第一個命中的。 */
    private record Occasion(List<String> cues, String greeting) {
    }

    private static final List<Occasion> OCCASIONS = List.of(
            new Occasion(List.of("生日", "慶生", "壽星"), "🎂 對了,先祝生日快樂!"),
            new Occasion(List.of("慶功", "升遷", "升職", "加薪", "達成目標", "犒賞", "上榜", "錄取"),
                    "🎉 恭喜!這種時刻值得好好犒賞自己。"),
            new Occasion(List.of("結婚紀念", "紀念日"), "💐 紀念日快樂!"),
            new Occasion(List.of("尾牙", "春酒"), "🥂 祝聚餐盡興!"));

    private OccasionGreeting() {
    }

    /** 偵測到場合就在回覆尾端附一句祝賀;已經帶祝賀的訊息(如訂位流程)不重複加。 */
    static IntentResult decorate(String userText, IntentResult result) {
        if (userText == null || result == null || result.message() == null) {
            return result;
        }
        if (result.action() == IntentResult.Action.FEEDBACK_RECEIVED
                || result.action() == IntentResult.Action.FALLBACK_TASK_CREATED) {
            return result;
        }
        Optional<String> greeting = OCCASIONS.stream()
                .filter(occasion -> occasion.cues().stream().anyMatch(userText::contains))
                .map(Occasion::greeting)
                .findFirst();
        if (greeting.isEmpty() || result.message().contains(greeting.get())) {
            return result;
        }
        return new IntentResult(result.action(), result.message() + "\n\n" + greeting.get(),
                result.task(), result.decision());
    }
}
