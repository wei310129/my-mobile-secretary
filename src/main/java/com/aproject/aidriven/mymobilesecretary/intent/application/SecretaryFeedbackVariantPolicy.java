package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationVoiceProfileService;
import java.util.List;

/** Controlled, evidence-free conversational acknowledgements for praise and dissatisfaction. */
final class SecretaryFeedbackVariantPolicy {

    static final int VARIANT_COUNT = 10;

    private static final List<String> PRAISE = List.of(
            "謝謝您，能幫上忙我也很開心。",
            "收到您的肯定，這次的處理方式有符合您的期待。",
            "太好了，您滿意就好。",
            "謝謝您，這次有達到您期待的水準。",
            "很高興這次有幫到您。",
            "謝謝您，這次有對到您的需要。",
            "收到，您的肯定我收下了。",
            "您這麼說，我就放心了。",
            "謝謝肯定，這次有抓到您在意的重點。",
            "這句稱讚很提神，我收下了。");

    private static final List<Reply> DISSATISFACTION = List.of(
            new Reply("收到，這次沒有符合您的期待。", "您最希望我先釐清哪一點？"),
            new Reply("我知道這次的結果讓您不滿意。", "您希望我先檢查內容、資料，還是回答方式？"),
            new Reply("收到，這次沒有對到您的需要。", "您希望我先從哪個部分重新確認？"),
            new Reply("明白，剛才的處理沒有幫上忙。", "您最希望我先確認哪個問題？"),
            new Reply("收到，這個結果沒有達到您的要求。", "您要我先釐清哪個差異？"),
            new Reply("我知道您對這次結果不滿意。", "您希望我先看哪一部分？"),
            new Reply("抱歉，這次的回答沒有解決您的問題。", "您要我先確認原本的哪個要求？"),
            new Reply("收到，這次我沒有抓準重點。", "您希望我先回到哪個重點？"),
            new Reply("我明白這個結果不合適。", "您最想先修正哪一點？"),
            new Reply("收到，這次需要先把問題釐清。", "您希望我先從哪裡開始？"));

    private SecretaryFeedbackVariantPolicy() {
    }

    static String praise(int index, ConversationVoiceProfileService.Settings settings) {
        int selected = Math.floorMod(index, PRAISE.size());
        if (selected == 0 && settings != null
                && (settings.assistantSelfName() != null || settings.userAddress() != null)) {
            String address = settings.userAddress() == null ? "您" : settings.userAddress();
            String self = settings.assistantSelfName() == null ? "我" : settings.assistantSelfName();
            return "謝謝%s，能幫上忙，%s也很開心。".formatted(address, self);
        }
        return PRAISE.get(selected);
    }

    static Reply dissatisfaction(
            int index, ConversationVoiceProfileService.Settings settings) {
        Reply selected = DISSATISFACTION.get(Math.floorMod(index, DISSATISFACTION.size()));
        if (settings != null && settings.userAddress() != null && index % DISSATISFACTION.size() == 0) {
            return new Reply(settings.userAddress() + "，" + selected.fact(), selected.question());
        }
        return selected;
    }

    record Reply(String fact, String question) {
    }
}
