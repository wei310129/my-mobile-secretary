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
        script = guardScheduleAnalysisCompleteness(text, script);
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

    private static IntentScript guardScheduleAnalysisCompleteness(
            String text, IntentScript script) {
        String compact = normalize(text);
        List<ScheduleAnalysisFacet> requested = java.util.Arrays.stream(
                        ScheduleAnalysisFacet.values())
                .filter(facet -> facet.requestedBy(compact))
                .toList();
        if (requested.isEmpty()) {
            return script;
        }

        List<IntentCommand> safe = script.commands().stream()
                .filter(Objects::nonNull)
                .filter(command -> !IntentService.isPotentiallyMutating(command.type()))
                .toList();
        List<ScheduleAnalysisFacet> missing = requested.stream()
                .filter(facet -> !facet.coveredBy(safe))
                .toList();
        boolean rejectedMutation = safe.size() != script.commands().stream()
                .filter(Objects::nonNull).count();
        if (missing.isEmpty() && !rejectedMutation) {
            return script;
        }

        List<IntentCommand> complete = new ArrayList<>(safe);
        String missingLabels = missing.isEmpty()
                ? "唯讀分析"
                : missing.stream().map(ScheduleAnalysisFacet::userLabel)
                        .collect(java.util.stream.Collectors.joining("、"));
        complete.add(new IntentCommand(IntentCommand.Type.UNKNOWN,
                null, null, null, null, null, null,
                "這個複合查詢目前還不能完整處理：%s。"
                        .formatted(missingLabels)
                        + "這次只會回答已可靠辨識的查詢，不會建立或修改資料。",
                null, null, null, null, null, null,
                scheduleAnalysisSource(text)));
        return new IntentScript(List.copyOf(complete));
    }

    private static String scheduleAnalysisSource(String text) {
        if (text == null || text.isBlank()) {
            return "複合行程分析";
        }
        return java.util.Arrays.stream(text.split("[，,;；。！？!?]"))
                .map(String::strip)
                .filter(part -> ScheduleAnalysisFacet.ADJACENT_GAP.requestedBy(normalize(part)))
                .findFirst()
                .orElse("複合行程分析");
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

    private enum ScheduleAnalysisFacet {
        BUSIEST_DAY("最忙日") {
            @Override
            boolean requestedBy(String text) {
                return containsAny(text,
                        "哪一天最忙", "哪天最忙", "最忙的一天", "最忙的日子",
                        "哪一天行程最多", "哪天行程最多", "行程最多的一天",
                        "排得最滿的日子", "排最滿的一天");
            }

            @Override
            boolean coveredBy(List<IntentCommand> commands) {
                return hasType(commands, IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY);
            }
        },
        LONGEST_SCHEDULE("最長行程") {
            @Override
            boolean requestedBy(String text) {
                return containsAny(text,
                        "最長的行程", "最久的行程", "最長的一筆", "哪一筆最長",
                        "耗時最久", "時間最長的行程", "排最久的活動");
            }

            @Override
            boolean coveredBy(List<IntentCommand> commands) {
                return hasType(commands, IntentCommand.Type.ASK_LONGEST_SCHEDULE);
            }
        },
        ADJACENT_GAP("指定行程前後的相鄰空檔") {
            @Override
            boolean requestedBy(String text) {
                boolean gap = containsAny(text,
                        "空檔", "空擋", "空閒", "空白時間", "空多久", "剩多少時間");
                boolean bothSides = text.contains("前後")
                        || text.matches(".*(?:前面|之前|前一段).{0,16}(?:後面|之後|後一段).*");
                boolean target = containsAny(text,
                        "那筆", "這筆", "該筆", "那個", "這個", "該個",
                        "行程", "活動", "會議", "課程", "預約");
                boolean query = containsAny(text,
                        "多少", "多長", "多久", "哪些", "列出", "告訴", "說明", "查", "看");
                return gap && bothSides && target && query;
            }

            @Override
            boolean coveredBy(List<IntentCommand> commands) {
                // ASK_SCHEDULE_GAP needs two explicit schedule targets. It cannot represent
                // the nearest free intervals around one selected schedule. A grounded UNKNOWN
                // may still cover the facet by clearly explaining that limitation.
                return commands.stream()
                        .filter(command -> command.type() == IntentCommand.Type.UNKNOWN)
                        .map(command -> normalize(command.reason()))
                        .anyMatch(this::requestedBy);
            }
        };

        private final String userLabel;

        ScheduleAnalysisFacet(String userLabel) {
            this.userLabel = userLabel;
        }

        abstract boolean requestedBy(String text);

        abstract boolean coveredBy(List<IntentCommand> commands);

        String userLabel() {
            return userLabel;
        }

        private static boolean hasType(
                List<IntentCommand> commands, IntentCommand.Type type) {
            return commands.stream().map(IntentCommand::type).anyMatch(type::equals);
        }
    }
}
