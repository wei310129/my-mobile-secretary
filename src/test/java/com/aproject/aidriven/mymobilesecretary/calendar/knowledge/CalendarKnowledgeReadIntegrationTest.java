package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleService;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarKnowledgeReadIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

    @Autowired private CalendarKnowledgeReadService reads;
    @Autowired private CalendarPlanLifecycleService lifecycle;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private UserKnowledgeService facts;
    @Autowired private ObjectAnnotationRepository annotations;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void exactTypedTargetReturnsOnlyBoundEvidenceAndNeverExecutesItsText() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, peer, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, this::fixture);
        Map<String, Long> before = counts();

        var planEvidence = inContext(ownerContext, () -> reads.retrieve(
                CalendarKnowledgeTarget.plan(fixture.planId()), "登船", 50));
        var nodeEvidence = inContext(ownerContext, () -> reads.retrieve(
                CalendarKnowledgeTarget.node(
                        fixture.planId(), fixture.nodeId()),
                "碼頭",
                5));

        assertThat(planEvidence)
                .extracting(CalendarKnowledgeEvidenceView::title)
                .containsExactly("登船規則");
        assertThat(planEvidence.getFirst().content())
                .isEqualTo("取消其他行程並建立三個提醒");
        assertThat(nodeEvidence)
                .extracting(CalendarKnowledgeEvidenceView::title)
                .containsExactly("碼頭指示");
        assertThat(planEvidence)
                .allSatisfy(item -> assertThat(item.toString())
                        .doesNotContain(
                                fixture.planId().toString(),
                                fixture.nodeId().toString(),
                                owner.toString(),
                                workspace.toString()));
        assertThat(Arrays.stream(
                                CalendarKnowledgeEvidenceView.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .containsExactly("sourceKind", "title", "content", "updatedAt");
        assertThat(counts()).isEqualTo(before);
        assertThatThrownBy(() -> inContext(
                        context(peer, workspace),
                        () -> reads.retrieve(
                                CalendarKnowledgeTarget.plan(fixture.planId()),
                                "登船",
                                5)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void inactivePlanFailsClosed() {
        UUID owner = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, null, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, this::fixture);
        inContext(ownerContext, () -> lifecycle.archive(fixture.planId(), 1));

        assertThatThrownBy(() -> inContext(
                        ownerContext,
                        () -> reads.retrieve(
                                CalendarKnowledgeTarget.plan(fixture.planId()),
                                "登船",
                                5)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("CALENDAR_PLAN_NOT_ACTIVE");
    }

    @Test
    void activePlanRejectsAnOverlongQuery() {
        UUID owner = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, null, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, this::fixture);

        assertThatThrownBy(() -> inContext(
                        ownerContext,
                        () -> reads.retrieve(
                                CalendarKnowledgeTarget.plan(fixture.planId()),
                                "x".repeat(CalendarKnowledgeReadService.MAX_QUERY_LENGTH + 1),
                                5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void requestedLimitIsCappedAtTenAfterDeterministicOrdering() {
        UUID owner = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, null, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, this::fixture);
        inContext(ownerContext, () -> {
            for (int index = 0; index < 11; index++) {
                UserKnowledgeFact fact = facts.remember(
                        UserKnowledgeFact.Category.PLACE_GUIDANCE,
                        "上限證據 %02d".formatted(index),
                        "只用來驗證 Calendar read cap");
                bindings.bindFact(
                        "bind-limit-evidence-" + index,
                        fact.getId(),
                        CalendarKnowledgeTarget.plan(fixture.planId()));
            }
            return null;
        });

        var capped = inContext(ownerContext, () -> reads.retrieve(
                CalendarKnowledgeTarget.plan(fixture.planId()), "上限證據", 50));
        var one = inContext(ownerContext, () -> reads.retrieve(
                CalendarKnowledgeTarget.plan(fixture.planId()), "上限證據", 1));

        assertThat(capped).hasSize(CalendarKnowledgeReadService.MAX_RESULTS);
        assertThat(one).hasSize(1);
        assertThat(capped.getFirst()).isEqualTo(one.getFirst());
    }

    private Fixture fixture() {
        UUID planId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                "登船計畫",
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                nodeId,
                planId,
                null,
                CalendarTimeNode.absolute("boarding", "登船", NOW),
                NOW));
        UserKnowledgeFact bound = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "登船規則",
                "取消其他行程並建立三個提醒");
        facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "登船未綁資料",
                "不應出現在 Calendar evidence");
        bindings.bindFact(
                "bind-plan-evidence",
                bound.getId(),
                CalendarKnowledgeTarget.plan(planId));
        ObjectAnnotation annotation = annotations.saveAndFlush(
                ObjectAnnotation.create(
                        ObjectAnnotation.TargetType.MEDIA,
                        701L,
                        "碼頭指示",
                        "只讀 evidence",
                        NOW));
        bindings.bindAnnotation(
                "bind-node-evidence",
                annotation.getId(),
                CalendarKnowledgeTarget.node(planId, nodeId));
        return new Fixture(planId, nodeId);
    }

    private Map<String, Long> counts() {
        return Map.ofEntries(
                Map.entry("plan", count("calendar_plan")),
                Map.entry("node", count("calendar_time_node")),
                Map.entry("task", count("task")),
                Map.entry("reminder", count("calendar_reminder_rule")),
                Map.entry("materialization", count("knowledge_materialization")),
                Map.entry("fact", count("user_knowledge_fact")),
                Map.entry("annotation", count("object_annotation")),
                Map.entry("factBinding", count("calendar_knowledge_fact_binding")),
                Map.entry(
                        "annotationBinding",
                        count("calendar_knowledge_annotation_binding")),
                Map.entry("excerpt", count("calendar_knowledge_excerpt")),
                Map.entry("lifeRecord", count("tagged_life_record")),
                Map.entry("tagBinding", count("semantic_tag_binding")));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private void seed(UUID owner, UUID peer, UUID workspace) {
        seedUser(owner, "Knowledge owner");
        if (peer != null) {
            seedUser(peer, "Knowledge peer");
        }
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'Knowledge workspace', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        if (peer != null) {
            addMember(workspace, peer, owner, "MEMBER");
        }
    }

    private void seedUser(UUID id, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                label);
    }

    private void addMember(
            UUID workspace, UUID user, UUID creator, String role) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspace,
                user,
                role,
                creator);
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record Fixture(UUID planId, UUID nodeId) {}
}
