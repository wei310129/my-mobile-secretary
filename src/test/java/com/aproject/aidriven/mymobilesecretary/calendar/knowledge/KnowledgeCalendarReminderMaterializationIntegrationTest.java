package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderDeliveryMode;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationChannel;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class KnowledgeCalendarReminderMaterializationIntegrationTest
        extends IntegrationTestBase {

    private static final String CHANNEL = "TEST";
    private static final String CONVERSATION_SCOPE =
            "knowledge-calendar-reminder-materialization";

    @Autowired private KnowledgeMaterializationService materializations;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private UserKnowledgeService facts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    @Test
    void nodeScopedFactCreatesOneRelativeCalendarReminderOnReplay() {
        WorkspaceContext context = seedContext("Calendar reminder materialization");
        Fixture fixture = inContext(context, () -> fixture("登船提醒"));
        KnowledgeMaterializationCommand.CreateCalendarReminder command =
                relativeReminder(fixture.nodeAId(), fixture.nodeARevision());
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-calendar-reminder-once",
                        source(fixture.binding()),
                        command));
        KnowledgeMaterializationConsent consent = consent(
                "confirm-calendar-reminder-once", proposal);

        KnowledgeMaterializationView created =
                inContext(context, () -> materializations.materialize(consent, command));
        KnowledgeMaterializationView replay =
                inContext(context, () -> materializations.materialize(consent, command));

        assertThat(replay).isEqualTo(created);
        assertThat(created.targetKind())
                .isEqualTo(KnowledgeMaterializationView.TargetKind.CALENDAR_REMINDER);
        UUID ruleId = UUID.fromString(created.resultReference());
        assertThat(count("calendar_reminder_rule")).isEqualTo(1L);
        assertThat(count("calendar_reminder_occurrence")).isEqualTo(1L);
        assertThat(count("task")).isZero();
        assertThat(count("task_reminder_rule")).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_reminder_rule
                        WHERE id = ? AND plan_id = ? AND node_id = ?
                          AND owner_kind = 'PERSONAL'
                          AND rule_kind = 'RELATIVE'
                          AND offset_seconds = -900
                          AND delivery_mode = 'ONCE'
                          AND status = 'ACTIVE'
                          AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        Long.class,
                        ruleId,
                        fixture.planId(),
                        fixture.nodeAId(),
                        context.workspaceId(),
                        context.actorId()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                                """
                                SELECT scheduled_at
                                FROM calendar_reminder_occurrence
                                WHERE rule_id = ?
                                """,
                                Instant.class,
                                ruleId))
                .isEqualTo(fixture.nodeATime().minus(Duration.ofMinutes(15)));
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM knowledge_materialization materialization
                        JOIN calendar_reminder_rule rule
                          ON rule.id =
                                materialization.calendar_reminder_rule_id
                         AND rule.plan_id = materialization.plan_id
                         AND rule.workspace_id = materialization.workspace_id
                         AND rule.created_by_user_id =
                                materialization.created_by_user_id
                        WHERE materialization.id = ?
                          AND materialization.status = 'COMPLETED'
                          AND materialization.target_kind =
                                'CALENDAR_REMINDER'
                        """,
                        Long.class,
                        created.id()))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT pg_get_constraintdef(oid)
                        FROM pg_constraint
                        WHERE conname =
                            'fk_knowledge_materialization_calendar_reminder'
                        """,
                        String.class))
                .contains(
                        "(calendar_reminder_rule_id, node_id, plan_id, workspace_id,"
                                + " created_by_user_id)");
    }

    @Test
    void nodeScopedSourceCannotMaterializeReminderForSiblingNode() {
        WorkspaceContext context = seedContext("Cross-node reminder materialization");
        Fixture fixture = inContext(context, () -> fixture("節點範圍"));
        KnowledgeMaterializationCommand.CreateCalendarReminder command =
                relativeReminder(fixture.nodeBId(), fixture.nodeBRevision());
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-cross-node-reminder",
                        source(fixture.binding()),
                        command));

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> materializations.materialize(
                                consent("confirm-cross-node-reminder", proposal),
                                command)))
                .isInstanceOf(NotFoundException.class);
        assertNoReminderMutation();
    }

    @Test
    void nodeRevisionChangedAfterPrepareProducesNoReminderMutation() {
        WorkspaceContext context = seedContext("Stale-node reminder materialization");
        Fixture fixture = inContext(context, () -> fixture("版本過期"));
        KnowledgeMaterializationCommand.CreateCalendarReminder command =
                relativeReminder(fixture.nodeAId(), fixture.nodeARevision());
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-stale-node-reminder",
                        source(fixture.binding()),
                        command));
        inContext(context, () -> {
            CalendarTimeNodeEntity node = nodes
                    .findByIdAndWorkspaceIdAndCreatedByUserId(
                            fixture.nodeAId(),
                            context.workspaceId(),
                            context.actorId())
                    .orElseThrow();
            node.reviseAbsolute(
                    fixture.nodeATime().plusSeconds(1),
                    fixture.nodeARevision(),
                    Instant.now(clock));
            nodes.saveAndFlush(node);
            return null;
        });

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> materializations.materialize(
                                consent("confirm-stale-node-reminder", proposal),
                                command)))
                .isInstanceOf(NotFoundException.class);
        assertNoReminderMutation();
    }

    private Fixture fixture(String title) {
        Instant nodeATime = Instant.now(clock)
                .plus(Duration.ofHours(2))
                .truncatedTo(ChronoUnit.MICROS);
        UUID planId = UUID.randomUUID();
        UUID nodeAId = UUID.randomUUID();
        UUID nodeBId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.point(nodeATime, ZoneId.of("Asia/Taipei")),
                Instant.now(clock)));
        CalendarTimeNodeEntity nodeA = nodes.saveAndFlush(
                CalendarTimeNodeEntity.create(
                        nodeAId,
                        planId,
                        null,
                        CalendarTimeNode.absolute(
                                "anchor-a", "登船", nodeATime),
                        Instant.now(clock)));
        CalendarTimeNodeEntity nodeB = nodes.saveAndFlush(
                CalendarTimeNodeEntity.create(
                        nodeBId,
                        planId,
                        null,
                        CalendarTimeNode.absolute(
                                "anchor-b",
                                "抵達",
                                nodeATime.plus(Duration.ofMinutes(30))),
                        Instant.now(clock)));
        UserKnowledgeFact fact = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                title,
                "登船前十五分鐘通知");
        CalendarKnowledgeBindingView binding = bindings.bindFact(
                "bind-calendar-reminder-" + planId,
                fact.getId(),
                CalendarKnowledgeTarget.node(planId, nodeAId));
        return new Fixture(
                planId,
                nodeAId,
                nodeA.getRevision(),
                nodeATime,
                nodeBId,
                nodeB.getRevision(),
                binding);
    }

    private static KnowledgeMaterializationCommand.CreateCalendarReminder
            relativeReminder(UUID nodeId, long revision) {
        return new KnowledgeMaterializationCommand.CreateCalendarReminder(
                nodeId,
                revision,
                new KnowledgeMaterializationCommand.CalendarReminderTiming.Relative(
                        Duration.ofMinutes(-15)),
                CalendarReminderDeliveryMode.ONCE,
                null,
                null,
                NotificationChannel.LOG);
    }

    private static KnowledgeMaterializationSource source(
            CalendarKnowledgeBindingView binding) {
        return new KnowledgeMaterializationSource(
                binding.sourceKind(),
                binding.id(),
                binding.sourceUpdatedAt(),
                binding.revision(),
                CHANNEL,
                CONVERSATION_SCOPE);
    }

    private static KnowledgeMaterializationConsent consent(
            String requestKey, KnowledgeMaterializationProposalView proposal) {
        return new KnowledgeMaterializationConsent(
                requestKey,
                proposal.id(),
                proposal.revision(),
                CHANNEL,
                CONVERSATION_SCOPE,
                true);
    }

    private void assertNoReminderMutation() {
        assertThat(count("calendar_reminder_rule")).isZero();
        assertThat(count("calendar_reminder_occurrence")).isZero();
        assertThat(count("task")).isZero();
        assertThat(count("task_reminder_rule")).isZero();
    }

    private WorkspaceContext seedContext(String label) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actor,
                label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                label,
                actor);
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record Fixture(
            UUID planId,
            UUID nodeAId,
            long nodeARevision,
            Instant nodeATime,
            UUID nodeBId,
            long nodeBRevision,
            CalendarKnowledgeBindingView binding) {
    }
}
