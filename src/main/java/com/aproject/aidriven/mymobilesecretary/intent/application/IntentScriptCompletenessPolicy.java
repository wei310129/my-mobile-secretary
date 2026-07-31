package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 防止結構化輸出只處理長句中的第一件事。
 *
 * <p>這不是以關鍵字執行業務，而是針對模型已辨識出的 command 做完整性檢查。每個 command
 * 應帶回它對應的原話片段；若有獨立可處理的子句沒有對應 command，就保留已確認的 command，
 * 並追加一個 UNKNOWN 讓使用者看見未處理的部分，而不是靜默遺失。</p>
 */
final class IntentScriptCompletenessPolicy {

    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[，,;；。！？!?]+$");
    private static final Pattern CLOCK = Pattern.compile("(?:\\d{1,2}(?::\\d{2}|點)|[一二三四五六七八九十兩]+點)");

    private IntentScriptCompletenessPolicy() {
    }

    static IntentScript apply(String text, IntentScript script) {
        if (script == null || script.commands() == null || script.commands().isEmpty()) {
            return script;
        }
        List<String> clauses = actionableClauses(text);
        if (clauses.size() <= 1) {
            return script;
        }

        List<IntentCommand> commands = script.commands().stream()
                .filter(Objects::nonNull).toList();
        if (commands.isEmpty()) {
            return script;
        }
        // 只有模型回傳每個 command 的原文片段時才能判斷覆蓋率。
        // 以子句數量猜測會把「幫我檢查 A，並考慮 B」這種單一複合操作誤拆成多意圖。
        if (!allCommandsHaveSource(commands)) {
            return script;
        }

        List<String> uncovered = clauses.stream()
                .filter(clause -> !isCovered(clause, commands)).toList();
        if (uncovered.isEmpty()) {
            return script;
        }

        List<IntentCommand> complete = new ArrayList<>(commands);
        for (String clause : uncovered) {
            complete.add(unknown(clause));
        }
        return new IntentScript(List.copyOf(complete));
    }

    static boolean hasIndependentActionableClause(String text) {
        return actionableClauses(text).size() > 1;
    }

    private static List<String> actionableClauses(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> clauses = new ArrayList<>();
        for (String part : text.replaceAll("\\s+", " ").split("(?<=[，,;；。！？!?])")) {
            String clause = TRAILING_PUNCTUATION.matcher(part.strip()).replaceFirst("").strip();
            if (!clause.isBlank() && isActionable(clause)) {
                clauses.add(clause);
            }
        }
        return List.copyOf(clauses);
    }

    private static boolean isActionable(String clause) {
        String compact = clause.replaceAll("\\s+", "");
        if (ReportedEventNoticePolicy.isReportedNotice(compact)
                || isPreparationInstruction(compact)) {
            return false;
        }
        if (containsAny(compact,
                "幫我", "提醒我", "記得", "建立", "新增", "安排", "取消", "刪除", "刪掉",
                "改到", "改成", "改期", "移到", "延後", "提前", "完成", "做完", "買", "繳",
                "接", "送", "查", "列出", "看看", "設定", "調整", "暫停", "恢復", "跳過",
                "訂位", "預約")) {
            return true;
        }
        return CLOCK.matcher(compact).find() && containsAny(compact,
                "會議", "開會", "上課", "英文課", "活動", "看診", "回診", "聚餐", "運動",
                "剪頭髮", "接送", "行程");
    }

    private static boolean isPreparationInstruction(String text) {
        return text.startsWith("請穿") || text.startsWith("要穿") || text.startsWith("記得穿");
    }

    private static boolean allCommandsHaveSource(List<IntentCommand> commands) {
        return commands.stream().allMatch(command -> command.sourceText() != null
                && !command.sourceText().isBlank());
    }

    private static boolean isCovered(String clause, List<IntentCommand> commands) {
        String normalizedClause = normalize(clause);
        return commands.stream()
                .map(IntentCommand::sourceText)
                .map(IntentScriptCompletenessPolicy::normalize)
                .anyMatch(source -> normalizedClause.contains(source) || source.contains(normalizedClause));
    }

    private static IntentCommand unknown(String clause) {
        return new IntentCommand(IntentCommand.Type.UNKNOWN, null, null, null, null,
                null, null, "我已保留可辨識的指示；另外「%s」要怎麼處理，請再確認一次。".formatted(clause),
                null, null, null, null, null, null, clause);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").replaceAll("[，,;；。！？!?]", "");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }
}
