package com.aproject.aidriven.mymobilesecretary.intent.application;

import com.aproject.aidriven.mymobilesecretary.travel.application.TravelPackingPreferenceService;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelPackingPreference;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelPackingPreference.Preference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Builds reusable packing drafts and manages explicit long-term suggestion preferences.
 * A one-off omission is never promoted to permanent memory without explicit wording.
 */
@Service
public class TravelPackingAnswerService {

    private static final Pattern REASON = Pattern.compile("[，,。；;]?因為(.+)$");
    private static final List<String> NEGATIVE_MARKERS = List.of("不要", "不用", "別");
    private static final List<String> POSITIVE_MARKERS = List.of("都要帶", "固定帶", "一定要帶", "都要建議");

    private final TravelPackingPreferenceService preferenceService;
    private final ConversationContextService conversationContextService;

    public TravelPackingAnswerService(TravelPackingPreferenceService preferenceService,
                                      ConversationContextService conversationContextService) {
        this.preferenceService = preferenceService;
        this.conversationContextService = conversationContextService;
    }

    /** Deterministic common-language entry point, including safe mutation-boundary handling. */
    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String normalized = normalize(text);
        if (normalized.isBlank()) return Optional.empty();

        if (isPreferenceListQuestion(normalized)) {
            return Optional.of(listPreferences());
        }
        if (isShortStandaloneCommand(normalized)) {
            Optional<PreferenceRequest> permanent = permanentPreference(normalized);
            if (permanent.isPresent()) {
                beforeMutation.run();
                PreferenceRequest request = permanent.get();
                return Optional.of(setPreference(
                        request.item(), request.preference().name(), request.reason()));
            }
            Optional<String> reset = resetPreferenceItem(normalized);
            if (reset.isPresent()) {
                beforeMutation.run();
                return Optional.of(setPreference(reset.get(), "CLEAR", null));
            }
            Optional<String> oneOff = oneOffOmission(normalized);
            if (oneOff.isPresent()) {
                return Optional.of(IntentResult.message(IntentResult.Action.PACKING_LIST_INFO,
                        "🧳 本次行李草案會略過「%s」。\n"
                                .formatted(oneOff.get())
                                + "- 這次的取捨不會改動長期偏好\n\n"
                                + "💾 如果以後都不想再看到它，請說「以後行李清單都不要建議%s」。"
                                .formatted(oneOff.get())));
            }
            Optional<String> ambiguous = ambiguousOmission(normalized);
            if (ambiguous.isPresent()) {
                return Optional.of(IntentResult.clarificationNeeded(
                        "要只在這次略過「%s」，還是以後的行李清單都不再建議？"
                                .formatted(ambiguous.get())));
            }
        }
        if (isPackingListRequest(normalized)) {
            return Optional.of(draft(text));
        }
        return Optional.empty();
    }

    public IntentResult draft(String requestText) {
        String tripContext = tripContext(requestText);
        boolean international = containsAny(tripContext,
                "出國", "國外", "日本", "韓國", "郵輪", "飛機", "航班");
        boolean cruise = tripContext.contains("郵輪");
        boolean japan = tripContext.contains("日本");
        boolean swimming = containsAny(tripContext, "游泳", "泳池", "玩水", "海灘", "浮潛");

        Map<String, List<String>> categories = new LinkedHashMap<>();
        add(categories, "衣物", "換洗衣物", "內衣褲襪", "睡衣", "薄外套", "好走的鞋");
        add(categories, "清潔與健康", "盥洗用品", "個人常備藥", "口罩", "防曬用品");
        add(categories, "電子用品", "手機", "充電線", "行動電源");
        if (international) {
            add(categories, "證件與金流", "護照", "登船／登機文件", "信用卡與外幣");
            add(categories, "通訊", "網路 SIM／漫遊方案");
        } else {
            add(categories, "證件與金流", "身分證", "交通票券", "信用卡與現金");
        }
        if (cruise) {
            add(categories, "郵輪", "暈船藥（依個人需要與醫囑）", "靠港隨身小包");
        }
        if (japan) {
            add(categories, "日本行程", "交通卡或交通 App", "翻譯 App");
        }
        if (swimming) {
            add(categories, "水上活動", "泳衣", "防水袋", "快乾毛巾");
        }

        List<TravelPackingPreference> preferences = preferenceService.list();
        List<String> omitted = new ArrayList<>();
        for (TravelPackingPreference preference : preferences) {
            if (preference.getPreference() == Preference.NEVER_SUGGEST) {
                if (removeItem(categories, preference.getNormalizedItem())) {
                    omitted.add(preferenceLabel(preference));
                }
            } else if (!containsItem(categories, preference.getNormalizedItem())) {
                categories.computeIfAbsent("依你的長期偏好", ignored -> new ArrayList<>())
                        .add(preference.getItemName());
            }
        }

        String contextLabel = cruise && japan ? "日本郵輪旅行"
                : cruise ? "郵輪旅行" : japan ? "日本旅行"
                : international ? "出國旅行" : "一般旅行";
        String body = categories.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> entry.getKey() + "｜" + String.join("、", entry.getValue()))
                .collect(Collectors.joining("\n"));
        String memory = omitted.isEmpty() ? ""
                : "\n\n🧠 已依長期偏好略過：\n" + String.join("\n", omitted);
        String conditional = swimming ? ""
                : "\n\n💡 目前行程沒有明確水上活動，因此沒有自行加入泳衣等用品。";
        return IntentResult.message(IntentResult.Action.PACKING_LIST_INFO,
                "🧳 %s行李清單草案：\n%s".formatted(contextLabel, body)
                        + memory + conditional
                        + "\n\n❓ 哪些項目要新增或刪除？刪除時我會區分只限這次或長期偏好。");
    }

    public IntentResult setPreference(String itemName, String filter, String reason) {
        String mode = filter == null ? "" : filter.strip().toUpperCase(Locale.ROOT);
        if ("CLEAR".equals(mode)) {
            boolean removed = preferenceService.forget(itemName);
            return IntentResult.message(IntentResult.Action.PACKING_PREFERENCE_UPDATED,
                    removed ? "🧠 已清除「%s」的長期行李偏好，之後會依行程重新判斷。"
                            .formatted(itemName)
                            : "🧠 「%s」目前沒有長期行李偏好，不需要清除。".formatted(itemName));
        }
        Preference preference = switch (mode) {
            case "ALWAYS", "ALWAYS_SUGGEST" -> Preference.ALWAYS_SUGGEST;
            case "NEVER", "NEVER_SUGGEST" -> Preference.NEVER_SUGGEST;
            default -> throw new IllegalArgumentException("packing preference missing scope");
        };
        TravelPackingPreference saved = preferenceService.remember(itemName, preference, reason);
        String decision = preference == Preference.NEVER_SUGGEST ? "不再主動建議" : "固定列入建議";
        String reasonLine = saved.getReason() == null ? "" : "\n- 原因｜" + saved.getReason();
        return IntentResult.message(IntentResult.Action.PACKING_PREFERENCE_UPDATED,
                "🧠 已記住長期行李偏好。\n"
                        + "- 項目｜" + saved.getItemName() + "\n"
                        + "- 設定｜" + decision + reasonLine);
    }

    public IntentResult listPreferences() {
        List<TravelPackingPreference> values = preferenceService.list();
        if (values.isEmpty()) {
            return IntentResult.message(IntentResult.Action.PACKING_LIST_INFO,
                    "🧠 目前沒有已儲存的長期行李偏好。");
        }
        String lines = values.stream().map(value -> {
            String decision = value.getPreference() == Preference.NEVER_SUGGEST
                    ? "不主動建議" : "固定建議";
            return value.getItemName() + "｜" + decision
                    + (value.getReason() == null ? "" : "｜" + value.getReason());
        }).collect(Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.PACKING_LIST_INFO,
                "🧠 你的長期行李偏好：\n" + lines);
    }

    private String tripContext(String requestText) {
        String current = normalize(requestText);
        if (looksLikeConcreteTrip(current) && !containsAny(current, "例如", "功能改善", "使用者")) {
            return current;
        }
        String previous = conversationContextService.snapshot().lastUserText();
        return previous == null ? current : normalize(previous);
    }

    private static boolean looksLikeConcreteTrip(String text) {
        return containsAny(text, "出國", "旅行", "旅遊", "郵輪", "飛機", "高鐵", "日本", "韓國");
    }

    private static boolean isPackingListRequest(String text) {
        return containsAny(text, "行李清單", "打包清單", "行前清單")
                && containsAny(text, "幫我", "草擬", "列", "準備", "產生", "整理", "套用");
    }

    private static boolean isPreferenceListQuestion(String text) {
        return containsAny(text, "我的行李偏好", "行李偏好有哪些", "記住哪些行李", "行李記憶");
    }

    private static boolean isShortStandaloneCommand(String text) {
        return text.length() <= 100 && !containsAny(text, "例如", "使用者", "功能改善", "有可能");
    }

    private static Optional<PreferenceRequest> permanentPreference(String text) {
        if (!containsAny(text, "以後", "未來", "每次", "下次開始")) return Optional.empty();
        String reason = extractReason(text);
        String withoutReason = removeReason(text);
        Optional<String> negative = itemAfterAny(withoutReason, NEGATIVE_MARKERS,
                "再", "主動", "建議", "列入", "加入", "帶", "打包");
        if (negative.isPresent()) {
            return Optional.of(new PreferenceRequest(negative.get(), Preference.NEVER_SUGGEST, reason));
        }
        Optional<String> positive = itemAfterAny(withoutReason, POSITIVE_MARKERS,
                "再", "主動", "建議", "列入", "加入", "帶", "打包");
        return positive.map(item -> new PreferenceRequest(
                item, Preference.ALWAYS_SUGGEST, reason));
    }

    private static Optional<String> oneOffOmission(String text) {
        if (!containsAny(text, "這次", "本次", "這趟")) return Optional.empty();
        return itemAfterAny(removeReason(text), NEGATIVE_MARKERS,
                "再", "建議", "列入", "加入", "帶", "打包");
    }

    private static Optional<String> ambiguousOmission(String text) {
        if (containsAny(text, "以後", "未來", "每次", "這次", "本次", "這趟")) {
            return Optional.empty();
        }
        return itemAfterAny(removeReason(text), NEGATIVE_MARKERS,
                "再", "建議", "列入", "加入", "帶", "打包");
    }

    private static Optional<String> resetPreferenceItem(String text) {
        if (!containsAny(text, "清除", "忘記", "恢復預設", "取消偏好")) return Optional.empty();
        String item = text.replace("清除", "").replace("忘記", "")
                .replace("恢復預設", "").replace("取消偏好", "")
                .replace("行李偏好", "").replace("的", "").strip();
        return cleanItem(item);
    }

    private static Optional<String> itemAfterAny(String text, List<String> markers,
                                                  String... removablePrefixes) {
        int at = -1;
        String found = null;
        for (String marker : markers) {
            int candidate = text.indexOf(marker);
            if (candidate >= 0 && (at < 0 || candidate < at)) {
                at = candidate;
                found = marker;
            }
        }
        if (found == null) return Optional.empty();
        String item = text.substring(at + found.length());
        boolean changed;
        do {
            changed = false;
            for (String prefix : removablePrefixes) {
                if (item.startsWith(prefix)) {
                    item = item.substring(prefix.length());
                    changed = true;
                }
            }
        } while (changed);
        return cleanItem(item);
    }

    private static Optional<String> cleanItem(String value) {
        String item = value.replaceAll("^[：:，,。；;]+|[？?！!。]+$", "").strip();
        if (item.isBlank() || item.length() > 30 || containsAny(item, "行李清單", "東西", "項目")) {
            return Optional.empty();
        }
        return Optional.of(item);
    }

    private static String extractReason(String text) {
        Matcher matcher = REASON.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static String removeReason(String text) {
        return REASON.matcher(text).replaceFirst("");
    }

    private static void add(Map<String, List<String>> categories,
                            String category, String... items) {
        categories.computeIfAbsent(category, ignored -> new ArrayList<>()).addAll(List.of(items));
    }

    private static boolean removeItem(Map<String, List<String>> categories, String normalizedItem) {
        boolean removed = false;
        for (List<String> items : categories.values()) {
            removed |= items.removeIf(item -> TravelPackingPreferenceService.normalize(item)
                    .equals(normalizedItem));
        }
        return removed;
    }

    private static boolean containsItem(Map<String, List<String>> categories, String normalizedItem) {
        return categories.values().stream().flatMap(List::stream)
                .map(TravelPackingPreferenceService::normalize)
                .anyMatch(normalizedItem::equals);
    }

    private static String preferenceLabel(TravelPackingPreference value) {
        return value.getItemName() + (value.getReason() == null ? "" : "｜" + value.getReason());
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s　]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) return true;
        }
        return false;
    }

    private record PreferenceRequest(String item, Preference preference, String reason) {
    }
}
