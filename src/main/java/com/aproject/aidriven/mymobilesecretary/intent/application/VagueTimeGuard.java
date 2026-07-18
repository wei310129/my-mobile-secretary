package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 模糊時間語守門(使用者 2026-07-16 裁決:模糊情境個案認定,系統可給建議但必須回問,不可自行預設)。
 *
 * LLM 可能把「下班後」「週末」「晚上」自行猜成具體時間直接建立
 * (客訴例:「帶小孩洗澡什麼時候變今天早上了?我沒有跟你說要改啊」),
 * 提醒的可靠度來自使用者信任,猜錯一次的傷害大於多問一句。
 * 因此在確定性層攔截:句子帶模糊時間語、又沒給具體鐘點/日期時,
 * 建立與改期一律不執行,把 LLM 的推測降級成「建議」回問使用者。
 */
final class VagueTimeGuard {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter SUGGEST_TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    /** 日期層級模糊:就算講了鐘點,是哪一天仍必須由使用者定(「週末早上」是週六還週日?)。 */
    private static final List<String> DATE_VAGUE = List.of(
            "週末", "周末", "月底", "月初", "年底", "過幾天", "過陣子", "改天",
            "有空再", "有空的時候", "找時間", "找個時間");

    /** 鐘點層級模糊:句子裡有具體鐘點(「下午兩點」)就不算模糊。 */
    private static final List<String> HOUR_VAGUE = List.of(
            "下班後", "下班之後", "下班前", "下班以前", "上班前", "上班之前",
            "早餐前", "午餐前", "晚餐前", "睡前", "起床後", "早點", "晚點", "一早",
            "早上", "上午", "中午", "下午", "傍晚", "晚上", "半夜", "凌晨");

    /**
     * 具體鐘點:數字(含中文數字)接「點/時/:」。
     * 「2小時」不會誤中(時前面是「小」不是數字);「晚一點」的「一點」會被視為具體鐘點,
     * 這是已知邊界——「一點」本身就同時是 1 點與「稍微」,寧可放行也不無限回問。
     */
    private static final Pattern CONCRETE_HOUR = Pattern.compile(
            "[0-9０-９〇一兩二三四五六七八九十]+\\s*[點点時时:：]");

    /** 具體日期:週幾、X月X日、X號、今天/明天/後天。 */
    private static final Pattern EXPLICIT_DAY = Pattern.compile(
            "週[一二三四五六日]|星期[一二三四五六日天]|禮拜[一二三四五六日天]"
                    + "|[0-9０-９一二三四五六七八九十]+\\s*[月號日]|今天|明天|後天|大後天");

    private VagueTimeGuard() {
    }

    /**
     * 建立/改期類指令的時間若源自模糊語,回傳「建議+回問」結果並擋下執行;
     * 查詢類指令與時間明確的句子回空,照常執行。
     */
    static Optional<IntentResult> clarify(String text, IntentCommand command) {
        if (!guardedType(command.type())) {
            return Optional.empty();
        }
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        Optional<String> vague = vagueExpression(normalized, command.title());
        if (vague.isEmpty()) {
            return Optional.empty();
        }
        String action = switch (command.type()) {
            case CREATE_TASK -> "建立這件待辦";
            case CREATE_SCHEDULE -> "排這個行程";
            case SUGGEST_FREE_SLOT, ASK_AVAILABILITY -> "用猜測的時間窗查詢";
            default -> "改時間";
        };
        StringBuilder message = new StringBuilder(vague.get().startsWith("每")
                ? "「%s」的固定提醒要有明確時點:請告訴我確切時間(例如「每天早上八點」「每週五下午三點」)。"
                        .formatted(vague.get())
                : "你說「%s」,這個時間我不自己認定:確切要定在哪天幾點?".formatted(vague.get()));
        suggestionFrom(command).ifPresent(suggestion ->
                message.append("\n💡 若要參考,我建議 %s;可以的話回覆這個時間,或直接講你要的。"
                        .formatted(suggestion)));
        message.append("\n你確認之前我不會").append(action).append("。");
        return Optional.of(IntentResult.clarificationNeeded(message.toString()));
    }

    /** 給其他引導流程共用的模糊判斷(如訂位流程決定要不要回問用餐時間)。 */
    static boolean hasVagueTime(String text, String title) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", "");
        return vagueExpression(normalized, title).isPresent();
    }

    /** 只守建立與改期:查詢/完成/取消不會憑空寫入猜測的時間。 */
    private static boolean guardedType(IntentCommand.Type type) {
        return type == IntentCommand.Type.CREATE_TASK
                || type == IntentCommand.Type.CREATE_SCHEDULE
                || type == IntentCommand.Type.RESCHEDULE_TASK
                || type == IntentCommand.Type.RESCHEDULE_SCHEDULE
                || type == IntentCommand.Type.SUGGEST_FREE_SLOT
                || type == IntentCommand.Type.ASK_AVAILABILITY;
    }

    private static Optional<String> vagueExpression(String normalized, String title) {
        // 出現在標題裡的詞是名稱的一部分,不是時間語(「歧義行程測試會議上午」的「上午」)
        String normalizedTitle = title == null ? "" : title.replaceAll("\\s+", "");
        boolean hasDay = EXPLICIT_DAY.matcher(normalized).find();
        boolean hasHour = CONCRETE_HOUR.matcher(normalized).find();
        Optional<String> recurringVague = recurringVague(normalized, hasDay, hasHour);
        if (recurringVague.isPresent()) {
            return recurringVague;
        }
        Optional<String> dateVague = DATE_VAGUE.stream()
                .filter(normalized::contains)
                .filter(token -> !normalizedTitle.contains(token))
                .findFirst();
        if (dateVague.isPresent() && !hasDay) {
            return dateVague;
        }
        Optional<String> hourVague = HOUR_VAGUE.stream()
                .filter(normalized::contains)
                .filter(token -> !normalizedTitle.contains(token))
                .findFirst();
        if (hourVague.isPresent() && !hasHour) {
            return hourVague;
        }
        return Optional.empty();
    }

    /**
     * 重複規則要有明確錨點才能建(使用者裁決 #21/#22):
     * 「每天」要有鐘點;「每週/每月」至少要有星期幾或幾號(有錨點時鐘點可留到期限日粒度)。
     */
    private static Optional<String> recurringVague(String normalized, boolean hasDay, boolean hasHour) {
        boolean weeklyOrMonthly = normalized.contains("每週") || normalized.contains("每周")
                || normalized.contains("每星期") || normalized.contains("每個月")
                || normalized.contains("每月");
        if (weeklyOrMonthly && !hasDay) {
            return Optional.of(normalized.contains("每月") || normalized.contains("每個月") ? "每月" : "每週");
        }
        if (normalized.contains("每天") && !hasHour) {
            return Optional.of("每天");
        }
        return Optional.empty();
    }

    /** 把 LLM 猜的時間降級成建議(它是猜測,不能直接寫入,但當參考選項很合適)。 */
    private static Optional<String> suggestionFrom(IntentCommand command) {
        String guessed = command.dueAt() != null && !command.dueAt().isBlank()
                ? command.dueAt() : command.startAt();
        if (guessed == null || guessed.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(guessed)
                    .withZoneSameInstant(TAIPEI).format(SUGGEST_TIME));
        } catch (RuntimeException e) {
            // LLM 給的字串爛掉就不提供建議,回問本身仍要送出
            return Optional.empty();
        }
    }
}
