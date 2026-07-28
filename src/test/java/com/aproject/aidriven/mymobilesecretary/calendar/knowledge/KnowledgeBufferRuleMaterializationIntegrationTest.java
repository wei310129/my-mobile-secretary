package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.geo.domain.Place;
import com.aproject.aidriven.mymobilesecretary.geo.persistence.PlaceRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.BufferRuleRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class KnowledgeBufferRuleMaterializationIntegrationTest
        extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-24T13:00:00Z");
    private static final String CHANNEL = "TEST";
    private static final String CONVERSATION_SCOPE =
            "knowledge-buffer-rule-materialization-test";

    @Autowired private KnowledgeMaterializationService materializations;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private UserKnowledgeService facts;
    @Autowired private PlaceRepository places;
    @Autowired private BufferRuleRepository bufferRules;
    @Autowired private Clock clock;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void typedFactBindingSetsExplicitBufferOnceWithoutChangingLearnedCounters() {
        WorkspaceContext context = seedContext("BufferRule materialization");
        Fixture fixture = inContext(context, () -> fixture("轉乘", "多留四十五分鐘"));
        KnowledgeMaterializationCommand.SetBufferRule command =
                new KnowledgeMaterializationCommand.SetBufferRule(
                        fixture.placeId(), 45, 0);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-buffer-rule-once",
                        source(fixture.binding()),
                        command));
        KnowledgeMaterializationConsent consent =
                new KnowledgeMaterializationConsent(
                        "confirm-buffer-rule-once",
                        proposal.id(),
                        proposal.revision(),
                        CHANNEL,
                        CONVERSATION_SCOPE,
                        true);

        KnowledgeMaterializationView created =
                inContext(context, () -> materializations.materialize(
                        consent, command));
        KnowledgeMaterializationView replay =
                inContext(context, () -> materializations.materialize(
                        consent, command));

        assertThat(replay).isEqualTo(created);
        assertThat(created.sourceKind())
                .isEqualTo(CalendarKnowledgeBindingView.SourceKind.FACT);
        assertThat(created.sourceBindingId()).isEqualTo(fixture.binding().id());
        assertThat(created.targetKind())
                .isEqualTo(KnowledgeMaterializationView.TargetKind.BUFFER_RULE);
        assertThat(created.resultReference())
                .isEqualTo(Long.toString(fixture.ruleId()));

        BufferRuleRow rule = bufferRule(fixture.ruleId());
        assertThat(rule.placeId()).isEqualTo(fixture.placeId());
        assertThat(rule.explicitBufferMinutes()).isEqualTo(45);
        assertThat(rule.explicitRevision()).isEqualTo(1);
        assertThat(rule.sampleCount()).isEqualTo(3);
        assertThat(rule.onTimeCount()).isEqualTo(1);
        assertThat(rule.totalOverrunMinutes()).isEqualTo(35);

        MaterializationResult result = materializationResult(proposal.id());
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.bufferRuleId()).isEqualTo(fixture.ruleId());
        assertThat(result.bufferRulePlaceId()).isEqualTo(fixture.placeId());
        assertThat(result.taskId()).isNull();
        assertThat(result.calendarTaskBindingId()).isNull();
        assertThat(result.nodeId()).isNull();
        assertThat(result.planningPreferenceId()).isNull();
        assertThat(result.taskReminderRuleId()).isNull();
        assertThat(result.calendarReminderRuleId()).isNull();
        assertOtherDownstreamTargetsAreEmpty();
        assertThat(count("knowledge_materialization")).isEqualTo(1L);
    }

    @Test
    void staleExplicitRevisionLeavesRuleAndEveryDownstreamTargetUnchanged() {
        WorkspaceContext context = seedContext("Stale BufferRule materialization");
        Fixture fixture = inContext(context, () -> fixture("候船", "多留三十分鐘"));
        inContext(context, () -> {
            BufferRule rule = bufferRules.findById(fixture.ruleId()).orElseThrow();
            rule.setExplicitBuffer(30, 0, Instant.now(clock));
            return bufferRules.saveAndFlush(rule);
        });
        KnowledgeMaterializationCommand.SetBufferRule staleCommand =
                new KnowledgeMaterializationCommand.SetBufferRule(
                        fixture.placeId(), 45, 0);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-stale-buffer-rule",
                        source(fixture.binding()),
                        staleCommand));
        KnowledgeMaterializationConsent consent =
                new KnowledgeMaterializationConsent(
                        "confirm-stale-buffer-rule",
                        proposal.id(),
                        proposal.revision(),
                        CHANNEL,
                        CONVERSATION_SCOPE,
                        true);

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> materializations.materialize(
                                consent, staleCommand)))
                .isInstanceOf(BusinessException.class);

        BufferRuleRow rule = bufferRule(fixture.ruleId());
        assertThat(rule.explicitBufferMinutes()).isEqualTo(30);
        assertThat(rule.explicitRevision()).isEqualTo(1);
        assertThat(rule.sampleCount()).isEqualTo(3);
        assertThat(rule.onTimeCount()).isEqualTo(1);
        assertThat(rule.totalOverrunMinutes()).isEqualTo(35);

        MaterializationResult result = materializationResult(proposal.id());
        assertThat(result.status()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(result.bufferRuleId()).isNull();
        assertThat(result.bufferRulePlaceId()).isNull();
        assertThat(result.taskId()).isNull();
        assertThat(result.calendarTaskBindingId()).isNull();
        assertThat(result.nodeId()).isNull();
        assertThat(result.planningPreferenceId()).isNull();
        assertThat(result.taskReminderRuleId()).isNull();
        assertThat(result.calendarReminderRuleId()).isNull();
        assertOtherDownstreamTargetsAreEmpty();
    }

    private Fixture fixture(String title, String text) {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        UserKnowledgeFact fact = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE, title, text);
        CalendarKnowledgeBindingView binding = bindings.bindFact(
                "buffer-rule-materialization-binding-" + planId,
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        Place place = places.saveAndFlush(Place.create(
                title + "地點",
                title + "碼頭",
                25.0478,
                121.5319,
                "TRANSIT",
                Instant.now(clock)));
        BufferRule rule = BufferRule.create(place.getId(), Instant.now(clock));
        rule.recordSample(10, Instant.now(clock));
        rule.recordSample(0, Instant.now(clock));
        rule.recordSample(25, Instant.now(clock));
        BufferRule saved = bufferRules.saveAndFlush(rule);
        return new Fixture(binding, place.getId(), saved.getId());
    }

    private KnowledgeMaterializationSource source(
            CalendarKnowledgeBindingView binding) {
        return new KnowledgeMaterializationSource(
                binding.sourceKind(),
                binding.id(),
                binding.sourceUpdatedAt(),
                binding.revision(),
                CHANNEL,
                CONVERSATION_SCOPE);
    }

    private BufferRuleRow bufferRule(long ruleId) {
        return jdbc.queryForObject(
                """
                SELECT place_id, explicit_buffer_minutes, explicit_revision,
                       sample_count, on_time_count, total_overrun_minutes
                FROM buffer_rule
                WHERE id = ?
                """,
                (row, rowNumber) -> new BufferRuleRow(
                        row.getLong("place_id"),
                        (Integer) row.getObject("explicit_buffer_minutes"),
                        row.getLong("explicit_revision"),
                        row.getInt("sample_count"),
                        row.getInt("on_time_count"),
                        row.getLong("total_overrun_minutes")),
                ruleId);
    }

    private MaterializationResult materializationResult(UUID proposalId) {
        return jdbc.queryForObject(
                """
                SELECT status, task_id, calendar_task_binding_id, node_id,
                       planning_preference_id, task_reminder_rule_id,
                       calendar_reminder_rule_id, buffer_rule_id,
                       buffer_rule_place_id
                FROM knowledge_materialization
                WHERE id = ?
                """,
                (row, rowNumber) -> new MaterializationResult(
                        row.getString("status"),
                        (Long) row.getObject("task_id"),
                        row.getObject("calendar_task_binding_id", UUID.class),
                        row.getObject("node_id", UUID.class),
                        (Integer) row.getObject("planning_preference_id"),
                        row.getObject("task_reminder_rule_id", UUID.class),
                        row.getObject("calendar_reminder_rule_id", UUID.class),
                        (Long) row.getObject("buffer_rule_id"),
                        (Long) row.getObject("buffer_rule_place_id")),
                proposalId);
    }

    private void assertOtherDownstreamTargetsAreEmpty() {
        assertThat(count("task")).isZero();
        assertThat(count("calendar_task_binding")).isZero();
        assertThat(count("calendar_time_node")).isZero();
        assertThat(count("planning_preference")).isZero();
        assertThat(count("task_reminder_rule")).isZero();
        assertThat(count("calendar_reminder_rule")).isZero();
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
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table, Long.class);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record Fixture(
            CalendarKnowledgeBindingView binding, long placeId, long ruleId) {}

    private record BufferRuleRow(
            long placeId,
            Integer explicitBufferMinutes,
            long explicitRevision,
            int sampleCount,
            int onTimeCount,
            long totalOverrunMinutes) {}

    private record MaterializationResult(
            String status,
            Long taskId,
            UUID calendarTaskBindingId,
            UUID nodeId,
            Integer planningPreferenceId,
            UUID taskReminderRuleId,
            UUID calendarReminderRuleId,
            Long bufferRuleId,
            Long bufferRulePlaceId) {}
}
