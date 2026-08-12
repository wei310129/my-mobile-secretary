package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationResponseStyle;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationVoicePreferenceTarget;
import com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicReplyEvidence;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Deterministic conversational intake for assistant self-name and respectful user address. */
@Service
public class ConversationVoicePreferenceConversationService {

    private static final Pattern BOTH = Pattern.compile(
            "(?:以後)?(?:你|您)(?:就)?叫(?:做)?[「\\\"]?([^，,；;。\\n]+?)[」\\\"]?"
                    + "(?:，|,|；|;|而且|並且)\\s*(?:你|您)?(?:要)?(?:稱呼|叫)我(?:為|做)?"
                    + "[「\\\"]?([^，,；;。\\n]+)[」\\\"]?");
    private static final Pattern ASSISTANT_NAME = Pattern.compile(
            "(?:以後)?(?:你|您)(?:就)?叫(?:做)?[「\\\"]?([^，,；;。？?\\n]+)[」\\\"]?");
    private static final Pattern USER_ADDRESS = Pattern.compile(
            "(?:以後)?(?:請)?(?:稱呼|叫)我(?:為|做)?[「\\\"]?([^，,；;。？?\\n]+)[」\\\"]?");

    private final ConversationVoicePreferenceDraftService drafts;
    private final ConversationVoiceProfileService profiles;

    public ConversationVoicePreferenceConversationService(
            ConversationVoicePreferenceDraftService drafts,
            ConversationVoiceProfileService profiles) {
        this.drafts = drafts;
        this.profiles = profiles;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (text == null || text.isBlank() || looksLikeQuotedProductExample(text)) {
            return Optional.empty();
        }
        String compact = compact(text);
        if (isResponseStyleInstruction(compact)) {
            beforeMutation.run();
            profiles.setResponseStyle(ConversationResponseStyle.CONCISE_WARM_SECRETARY);
            return Optional.of(committed(
                    "好的，回答偏好已保存為簡短、自然、有溫度的秘書口吻。"));
        }
        if (isResponseStyleReset(compact)) {
            beforeMutation.run();
            profiles.resetResponseStyle();
            return Optional.of(committed("好的，回答偏好已恢復為系統預設。"));
        }
        if (isResponseStyleQuery(compact)) {
            return Optional.of(currentResponseStyleReply());
        }
        Optional<ConversationVoicePreferenceDraft> pending = drafts.current();
        if (pending.isPresent()) {
            Optional<IntentResult> continued = continueDraft(
                    text, pending.orElseThrow(), beforeMutation);
            if (continued.isPresent()) {
                return continued;
            }
        }
        if (isProfileQuery(compact)) {
            return Optional.of(currentSettingsReply());
        }
        Optional<Names> both = names(text);
        if (both.isPresent()) {
            beforeMutation.run();
            Names names = both.orElseThrow();
            ConversationVoiceProfileService.Settings saved =
                    profiles.setBoth(names.assistantName(), names.userAddress());
            return Optional.of(savedBoth(saved));
        }
        if (containsAny(compact, "恢復預設稱呼", "稱呼恢復預設", "重設所有稱呼")) {
            beforeMutation.run();
            profiles.resetAll();
            return Optional.of(committed(
                    "好的，稱呼設定已恢復為預設值：服務自稱為「我」，您的稱呼為「您」。"));
        }
        if (containsAny(compact, "不要再叫我")) {
            beforeMutation.run();
            profiles.resetUserAddress();
            return Optional.of(committed("好的，您的稱呼已恢復為預設的「您」。"));
        }
        if (isAssistantNameReset(compact)) {
            beforeMutation.run();
            profiles.resetAssistantName();
            return Optional.of(committed("好的，服務名稱已移除，自稱已恢復為「我」。"));
        }
        Optional<String> assistant = assistantName(text);
        if (assistant.isPresent()) {
            beforeMutation.run();
            ConversationVoiceProfileService.Settings saved =
                    profiles.setAssistantName(assistant.orElseThrow());
            return Optional.of(committed(
                    "好的，服務名稱已設定為「%s」。".formatted(saved.assistantSelfName())));
        }
        Optional<String> userAddress = userAddress(text);
        if (userAddress.isPresent()) {
            beforeMutation.run();
            ConversationVoiceProfileService.Settings saved =
                    profiles.setUserAddress(userAddress.orElseThrow());
            return Optional.of(committed(
                    "好的，您的稱呼已設定為「%s」。".formatted(saved.userAddress())));
        }
        if (isGenericChangeRequest(compact)) {
            beforeMutation.run();
            drafts.start();
            return Optional.of(ask("voice.target", "voice.target",
                    "可以。您想先改我怎麼稱呼您，還是先替我取一個名字？"));
        }
        return Optional.empty();
    }

    private Optional<IntentResult> continueDraft(
            String text, ConversationVoicePreferenceDraft draft, Runnable beforeMutation) {
        String compact = compact(text);
        if (containsAny(compact, "算了", "不改了", "取消修改", "先不要改")) {
            beforeMutation.run();
            drafts.cancel(draft);
            return Optional.of(plain("好的，我先保留目前的稱呼設定。"));
        }
        Optional<Names> supplied = names(text);
        if (supplied.isPresent()) {
            beforeMutation.run();
            try {
                Names values = supplied.orElseThrow();
                return Optional.of(savedBoth(drafts.answerBothAndComplete(
                        draft, values.assistantName(), values.userAddress())));
            } catch (IllegalArgumentException invalid) {
                return Optional.of(ask("voice.assistant-name", "voice.assistantName",
                        "這組稱呼目前無法使用。您可以先替我取一個簡短的名字嗎？"));
            }
        }
        if (draft.getTarget() == ConversationVoicePreferenceTarget.UNDECIDED) {
            ConversationVoicePreferenceTarget target = requestedTarget(compact);
            if (target == null) {
                return Optional.of(ask("voice.target", "voice.target",
                        "您想先改我怎麼稱呼您，還是先替我取一個名字？"));
            }
            beforeMutation.run();
            ConversationVoicePreferenceDraft selected = drafts.choose(draft, target);
            return Optional.of(target == ConversationVoicePreferenceTarget.USER_ADDRESS
                    ? ask("voice.user-address", "voice.userAddress", "您希望我怎麼稱呼您？")
                    : ask("voice.assistant-name", "voice.assistantName", "您希望我叫什麼名字？"));
        }
        try {
            if (needsAssistant(draft)) {
                beforeMutation.run();
                String value = assistantName(text).orElseGet(() -> plainValue(text));
                ConversationVoicePreferenceDraft updated = drafts.answerAssistant(draft, value);
                if (updated.getTarget() == ConversationVoicePreferenceTarget.BOTH
                        && updated.getUserAddress() == null) {
                    return Optional.of(ask("voice.user-address", "voice.userAddress",
                            "好的。接著，您希望我怎麼稱呼您？"));
                }
                return Optional.of(complete(updated));
            }
            if (needsUserAddress(draft)) {
                beforeMutation.run();
                String value = userAddress(text).orElseGet(() -> plainValue(text));
                return Optional.of(complete(drafts.answerUserAddress(draft, value)));
            }
        } catch (IllegalArgumentException invalid) {
            String prompt = needsAssistant(draft)
                    ? "這個名字目前無法使用。您可以替我取一個簡短的名字嗎？"
                    : "這個稱呼目前無法使用。您可以換一個簡短稱呼嗎？";
            return Optional.of(ask(
                    needsAssistant(draft) ? "voice.assistant-name" : "voice.user-address",
                    needsAssistant(draft) ? "voice.assistantName" : "voice.userAddress",
                    prompt));
        }
        return Optional.empty();
    }

    private IntentResult complete(ConversationVoicePreferenceDraft draft) {
        ConversationVoiceProfileService.Settings saved = drafts.applyAndComplete(draft);
        return switch (draft.getTarget()) {
            case ASSISTANT_NAME -> committed(
                    "好的，服務名稱已設定為「%s」。".formatted(saved.assistantSelfName()));
            case USER_ADDRESS -> committed(
                    "好的，您的稱呼已設定為「%s」。".formatted(saved.userAddress()));
            case BOTH -> savedBoth(saved);
            case UNDECIDED -> throw new IllegalStateException("voice preference target is undecided");
        };
    }

    private IntentResult currentSettingsReply() {
        ConversationVoiceProfileService.Settings settings = profiles.current();
        String assistant = settings.assistantSelfName() == null
                ? "目前沒有設定服務名稱，自稱設定是「我」"
                : "目前的服務名稱是「%s」".formatted(settings.assistantSelfName());
        String address = settings.userAddress() == null
                ? "您的稱呼設定是「您」"
                : "您的稱呼設定是「%s」".formatted(settings.userAddress());
        return plain(assistant + "；" + address + "。");
    }

    private IntentResult currentResponseStyleReply() {
        ConversationVoiceProfileService.Settings settings = profiles.current();
        if (settings.responseStyle() == ConversationResponseStyle.CONCISE_WARM_SECRETARY) {
            return plain("您目前保存的回答偏好是簡短、自然、有溫度的秘書口吻。");
        }
        return plain("您目前沒有另外保存回答風格；回覆使用系統預設的秘書口吻。");
    }

    private static IntentResult savedBoth(ConversationVoiceProfileService.Settings settings) {
        return committed("好的，服務名稱已設定為「%s」，您的稱呼已設定為「%s」。"
                .formatted(settings.assistantSelfName(), settings.userAddress()));
    }

    private static IntentResult committed(String message) {
        return IntentResult.plainMessage(IntentResult.Action.CONTEXT_UPDATED, message,
                PublicReplyEvidence.PREFERENCE_COMMITTED);
    }

    private static IntentResult plain(String message) {
        return IntentResult.plainMessage(IntentResult.Action.CONTEXT_UPDATED, message);
    }

    private static IntentResult ask(String code, String slot, String prompt) {
        ClarificationStep step = ClarificationStep.blocking(code, slot, prompt, 10);
        return new IntentResult(IntentResult.Action.CLARIFICATION_NEEDED, prompt,
                null, null, null, null, null, step.nextQuestion(),
                IntentResult.ResponsePresentation.PLAIN,
                Set.of(PublicReplyEvidence.PENDING_COMMITTED,
                        PublicReplyEvidence.ZERO_MUTATION_VERIFIED));
    }

    private static Optional<Names> names(String text) {
        Matcher matcher = BOTH.matcher(text.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new Names(clean(matcher.group(1)), clean(matcher.group(2))));
    }

    private static Optional<String> assistantName(String text) {
        Matcher matcher = ASSISTANT_NAME.matcher(text.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = clean(matcher.group(1));
        return containsAny(compact(value), "什麼", "甚麼", "哪個")
                ? Optional.empty() : Optional.of(value);
    }

    private static Optional<String> userAddress(String text) {
        Matcher matcher = USER_ADDRESS.matcher(text.strip());
        if (!matcher.find()) {
            return Optional.empty();
        }
        String value = clean(matcher.group(1));
        return containsAny(compact(value), "什麼", "甚麼", "怎麼")
                ? Optional.empty() : Optional.of(value);
    }

    private static ConversationVoicePreferenceTarget requestedTarget(String compact) {
        if (containsAny(compact, "兩個都", "全部都", "都要改")) {
            return ConversationVoicePreferenceTarget.BOTH;
        }
        if (containsAny(compact, "你的名字", "你叫什麼", "替你取名", "改你的")) {
            return ConversationVoicePreferenceTarget.ASSISTANT_NAME;
        }
        if (containsAny(compact, "叫我", "稱呼我", "對我的稱呼", "怎麼叫我")) {
            return ConversationVoicePreferenceTarget.USER_ADDRESS;
        }
        return null;
    }

    private static boolean needsAssistant(ConversationVoicePreferenceDraft draft) {
        return (draft.getTarget() == ConversationVoicePreferenceTarget.ASSISTANT_NAME
                || draft.getTarget() == ConversationVoicePreferenceTarget.BOTH)
                && draft.getAssistantSelfName() == null;
    }

    private static boolean needsUserAddress(ConversationVoicePreferenceDraft draft) {
        return (draft.getTarget() == ConversationVoicePreferenceTarget.USER_ADDRESS
                || draft.getTarget() == ConversationVoicePreferenceTarget.BOTH)
                && draft.getUserAddress() == null;
    }

    private static boolean isProfileQuery(String compact) {
        return containsAny(compact,
                "目前的稱呼設定", "現在的稱呼設定", "你現在叫什麼", "你叫什麼名字",
                "你都怎麼稱呼我", "你怎麼稱呼我");
    }

    private static boolean isGenericChangeRequest(String compact) {
        return containsAny(compact,
                "可以改稱呼嗎", "能改稱呼嗎", "修改稱呼", "更改稱呼", "想改稱呼");
    }

    private static boolean isResponseStyleInstruction(String compact) {
        boolean future = containsAny(compact, "以後", "之後", "往後", "未來");
        boolean style = containsAny(compact,
                "這種方式", "這個方式", "這樣", "這種口吻", "這個口吻",
                "簡短自然", "自然有溫度", "秘書口吻", "秘書式口吻");
        boolean reply = containsAny(compact, "回答", "回覆", "答覆", "對答", "說話");
        return future && style && reply;
    }

    private static boolean isResponseStyleReset(String compact) {
        return containsAny(compact,
                "回答偏好恢復預設", "回答風格恢復預設", "回覆風格恢復預設",
                "取消回答偏好", "清除回答偏好");
    }

    private static boolean isResponseStyleQuery(String compact) {
        return containsAny(compact,
                "目前的回答偏好", "現在的回答偏好", "目前回答風格",
                "現在回答風格", "目前回覆風格", "口吻設定是什麼");
    }

    private boolean isAssistantNameReset(String compact) {
        if (containsAny(compact,
                "移除你的名字", "恢復你的預設名字", "不要再有名字", "不要再叫自己")) {
            return true;
        }
        if (!compact.contains("不要再叫") || compact.contains("不要再叫我")) {
            return false;
        }
        String configuredName = profiles.current().assistantSelfName();
        return configuredName != null
                && compact.contains("不要再叫" + compact(configuredName));
    }

    private static boolean looksLikeQuotedProductExample(String text) {
        String compact = compact(text);
        return containsAny(compact, "使用者說", "例如使用者", "功能需求", "開發需求", "測試案例");
    }

    private static String plainValue(String text) {
        return clean(text.replaceAll("[。！？!?]+$", ""));
    }

    private static String clean(String value) {
        return value.strip().replaceAll("^[「\\\"]|[」\\\"]$", "").strip();
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "")
                .replaceAll("[。！？!?]+$", "");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private record Names(String assistantName, String userAddress) {
    }
}
