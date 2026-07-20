package com.aproject.aidriven.mymobilesecretary.planning.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 草稿、待辦事項、行程提醒、行程與知識紀錄之間的唯一轉換守門。
 * 草稿只能向外轉；三種規劃類別可互轉，知識紀錄則只能由草稿建立且不再轉為規劃類別。
 */
public final class PlanningItemTransitionPolicy {

    private PlanningItemTransitionPolicy() {
    }

    public static Decision assess(PlanningItemType source, PlanningItemType target,
                                  PlanningItemShape shape) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(shape, "shape");

        if (source == target) {
            return Decision.allowDecision();
        }
        if (target == PlanningItemType.DRAFT) {
            return Decision.rejected("已離開草稿的項目不可轉回草稿");
        }
        if (source == PlanningItemType.KNOWLEDGE) {
            return Decision.rejected("知識紀錄是長期保存資料，不轉成待辦、行程提醒或行程");
        }
        if (target == PlanningItemType.KNOWLEDGE && source != PlanningItemType.DRAFT) {
            return Decision.rejected("知識紀錄只能由草稿轉入");
        }

        List<String> missing = new ArrayList<>();
        if (shape.title() == null || shape.title().isBlank()) {
            missing.add("標題或內容");
        }
        switch (target) {
            case DRAFT -> throw new IllegalStateException("draft target was handled above");
            case TODO -> {
                if (shape.startAt() != null || shape.endAt() != null) {
                    missing.add("待辦事項不可保留執行日期或時間；請明確移除時間");
                }
            }
            case SCHEDULE_REMINDER -> {
                if (shape.startAt() == null) {
                    missing.add("提醒時間");
                }
                if (shape.endAt() != null) {
                    missing.add("行程提醒是單一時點，不可帶占用時段");
                }
            }
            case SCHEDULE -> {
                if (shape.startAt() == null) missing.add("開始時間");
                if (shape.endAt() == null) missing.add("結束時間");
                if (shape.startAt() != null && shape.endAt() != null
                        && !shape.endAt().isAfter(shape.startAt())) {
                    missing.add("有效的開始與結束時間");
                }
                if (!shape.actorPresenceRequired()) missing.add("本人必須到場或上線");
                if (!shape.conflictsChecked()) missing.add("撞期檢查");
                if (!shape.feasibilityChecked()) missing.add("地點與可行性檢查");
            }
            case KNOWLEDGE -> {
                // 知識只需要可長期保存的實際內容，不需要日期、完成狀態或撞期檢查。
            }
        }
        return missing.isEmpty() ? Decision.allowDecision() : Decision.missing(missing);
    }

    public record Decision(boolean allowed, List<String> requirements) {
        public Decision {
            requirements = List.copyOf(requirements);
        }

        private static Decision allowDecision() {
            return new Decision(true, List.of());
        }

        private static Decision rejected(String reason) {
            return new Decision(false, List.of(reason));
        }

        private static Decision missing(List<String> requirements) {
            return new Decision(false, requirements);
        }
    }
}
