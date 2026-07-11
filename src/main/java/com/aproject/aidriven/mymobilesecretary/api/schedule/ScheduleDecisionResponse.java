package com.aproject.aidriven.mymobilesecretary.api.schedule;

import com.aproject.aidriven.mymobilesecretary.planner.domain.FeasibilityIssue;
import com.aproject.aidriven.mymobilesecretary.schedule.application.ScheduleService.ScheduleDecision;
import java.util.List;

/**
 * 提出/改時間行程的回應:行程 + 可行性驗算結果。
 *
 * feasible=true → 行程已自動 CONFIRMED;
 * feasible=false → 行程留在 PROPOSED,issues 說明原因,options 是可用的下一步。
 */
public record ScheduleDecisionResponse(
        ScheduleResponse schedule,
        boolean feasible,
        List<IssueResponse> issues,
        List<String> options
) {

    /** 單一可行性問題。 */
    public record IssueResponse(String type, String message, Long relatedScheduleId) {

        static IssueResponse from(FeasibilityIssue issue) {
            return new IssueResponse(issue.type().name(), issue.message(), issue.relatedScheduleId());
        }
    }

    /** 由 service 結果轉成回應 DTO。 */
    public static ScheduleDecisionResponse from(ScheduleDecision decision) {
        boolean feasible = decision.feasibility().feasible();
        return new ScheduleDecisionResponse(
                ScheduleResponse.from(decision.item()),
                feasible,
                decision.feasibility().issues().stream().map(IssueResponse::from).toList(),
                // 不可行時給使用者的選項(對應 API 動作)
                feasible ? List.of() : List.of(
                        "reschedule:改時間重新驗算",
                        "confirm:我已安排好(強制確認)",
                        "park:暫無想法,先放 pending",
                        "reject:放棄這個行程"));
    }
}
