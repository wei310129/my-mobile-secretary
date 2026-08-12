package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.text.Normalizer;
import java.util.Optional;

/** High-confidence read-only shortcuts for common secretary queries. */
final class SecretaryTurnRouter {

    private SecretaryTurnRouter() {
    }

    static Optional<IntentCommand> route(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String compact = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .replaceAll("[，。！？!?]+$", "");
        if (containsAny(compact,
                "新增", "建立", "記下", "提醒我", "幫我排", "取消", "刪除", "清空",
                "改成", "改到", "修改", "完成", "做完", "買到", "綁定", "移除")) {
            return Optional.empty();
        }
        if (containsAny(compact,
                "推薦", "建議", "順便", "順路", "適合", "可以做", "可以完成",
                "分析", "比較", "最忙", "最長", "最短", "空檔", "哪一天", "哪一筆", "前後各")) {
            return Optional.empty();
        }
        boolean taskDomain = containsAny(compact, "待辦", "任務", "事情");
        boolean scheduleDomain = containsAny(compact, "行程", "日曆", "安排");
        boolean query = containsAny(compact,
                "有什麼", "有哪些", "還有", "列出", "查看", "看看", "接下來", "等等",
                "待會", "要做什麼", "要幹嘛", "是什麼", "呢", "總覽", "摘要")
                || compact.equals("待辦") || compact.equals("任務")
                || compact.equals("行程") || compact.equals("安排");
        if (!query) return Optional.empty();

        IntentCommand.Type type;
        if (taskDomain && !scheduleDomain) {
            type = IntentCommand.Type.LIST_TASKS;
        } else if (scheduleDomain && !taskDomain && !isGenericArrangementQuestion(compact)) {
            type = IntentCommand.Type.LIST_SCHEDULES;
        } else if (taskDomain || scheduleDomain || isGenericArrangementQuestion(compact)) {
            type = IntentCommand.Type.LIST_AGENDA;
        } else {
            return Optional.empty();
        }
        IntentOptions options = IntentOptions.empty().withFilter(filter(compact));
        return Optional.of(new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null, options, text));
    }

    private static boolean isGenericArrangementQuestion(String compact) {
        return containsAny(compact, "有什麼事", "要做什麼", "要幹嘛", "接下來", "等等", "待會")
                && !containsAny(compact, "待辦", "任務", "行程", "日曆");
    }

    private static String filter(String compact) {
        if (containsAny(compact, "明天", "明日")) return "TOMORROW";
        if (containsAny(compact, "這週", "本週", "一週")) return "WEEK";
        if (containsAny(compact, "今天", "今日")) return "TODAY";
        return "UPCOMING";
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }
}
