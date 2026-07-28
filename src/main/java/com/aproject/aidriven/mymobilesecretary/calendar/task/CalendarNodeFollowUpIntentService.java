package com.aproject.aidriven.mymobilesecretary.calendar.task;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CalendarNodeFollowUpIntentService {

    private final JdbcTemplate jdbc;
    private final CalendarNodeFollowUpTaskService followUps;

    public CalendarNodeFollowUpIntentService(
            JdbcTemplate jdbc, CalendarNodeFollowUpTaskService followUps) {
        this.jdbc = jdbc;
        this.followUps = followUps;
    }

    public IntentResult create(IntentCommand command) {
        String planTitle = command.safeOptions().referenceTitle();
        String nodeLabel = command.safeOptions().alias();
        if (planTitle == null || planTitle.isBlank()
                || nodeLabel == null || nodeLabel.isBlank()) {
            return IntentResult.clarificationNeeded(
                    "請告訴我是哪個行程、哪個時間節點，以及後續要追蹤的待辦。");
        }
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        List<Target> matches = jdbc.query(
                """
                SELECT plan.id AS plan_id, node.id AS node_id
                  FROM calendar_plan plan
                  JOIN calendar_time_node node
                    ON node.plan_id = plan.id
                   AND node.workspace_id = plan.workspace_id
                   AND node.created_by_user_id = plan.created_by_user_id
                 WHERE lower(plan.title) = lower(?)
                   AND lower(node.label) = lower(?)
                   AND plan.workspace_id = ?
                   AND plan.created_by_user_id = ?
                 ORDER BY plan.created_at DESC, node.created_at
                 LIMIT 3
                """,
                (row, ignored) -> new Target(
                        row.getObject("plan_id", UUID.class),
                        row.getObject("node_id", UUID.class)),
                planTitle.strip(),
                nodeLabel.strip(),
                context.workspaceId(),
                context.actorId());
        if (matches.isEmpty()) {
            return IntentResult.clarificationNeeded(
                    "找不到唯一符合「%s」的「%s」節點，請確認行程與節點名稱。"
                            .formatted(planTitle, nodeLabel));
        }
        if (matches.size() > 1) {
            return IntentResult.clarificationNeeded(
                    "找到多個同名行程節點，請補上日期或更完整的行程名稱。");
        }
        int delayMinutes = command.safeOptions().shiftMinutes() == null
                ? 0
                : command.safeOptions().shiftMinutes();
        Target target = matches.getFirst();
        var task = followUps.createAfterNode(
                "intent-node-follow-up:" + RequestCorrelationContext.currentId(),
                target.planId(),
                target.nodeId(),
                command.title(),
                Duration.ofMinutes(delayMinutes));
        return IntentResult.taskMessage(
                IntentResult.Action.TASK_CREATED,
                "已建立待辦「%s」，期限連結在「%s」的「%s」節點後%s；沒有新增日曆提醒或移動行程。"
                        .formatted(
                                task.getTitle(),
                                planTitle,
                                nodeLabel,
                                delayMinutes == 0 ? "" : " " + delayMinutes + " 分鐘"),
                task);
    }

    private record Target(UUID planId, UUID nodeId) {}
}
