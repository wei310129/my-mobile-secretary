package com.aproject.aidriven.mymobilesecretary.planner.domain;

import java.util.List;

/**
 * 可行性驗算結果。「要可行才放行」:feasible 才允許自動 CONFIRMED,
 * 否則行程留在 PROPOSED,由使用者決定(改時間 / 強制確認 / 轉 PENDING / 放棄)。
 */
public record FeasibilityResult(boolean feasible, List<FeasibilityIssue> issues) {

    public static FeasibilityResult ok() {
        return new FeasibilityResult(true, List.of());
    }

    public static FeasibilityResult withIssues(List<FeasibilityIssue> issues) {
        return new FeasibilityResult(issues.isEmpty(), List.copyOf(issues));
    }
}
