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
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarKnowledgeBindingIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarPlanLifecycleService lifecycle;
    @Autowired private CalendarKnowledgeIntentTargetResolver intentTargets;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private UserKnowledgeService facts;
    @Autowired private ObjectAnnotationRepository annotations;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void factAndAnnotationUseSeparateRealFkBindingsWithIdempotentReplay() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "知識綁定者");
        WorkspaceContext context = context(actor, workspace);
        Fixture fixture = inContext(context, () -> fixture("登船"));
        UserKnowledgeFact fact = inContext(context, () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "碼頭報到",
                "至少提早四十分鐘"));
        ObjectAnnotation annotation = inContext(context, () -> annotations.saveAndFlush(
                ObjectAnnotation.create(
                        ObjectAnnotation.TargetType.MEDIA,
                        91L,
                        "船票",
                        "登船時出示",
                        NOW)));

        CalendarKnowledgeBindingView factBinding = inContext(context, () -> bindings.bindFact(
                "bind-fact-once",
                fact.getId(),
                CalendarKnowledgeTarget.node(fixture.planId(), fixture.nodeId())));
        CalendarKnowledgeBindingView replay = inContext(context, () -> bindings.bindFact(
                "bind-fact-once",
                fact.getId(),
                CalendarKnowledgeTarget.node(fixture.planId(), fixture.nodeId())));
        CalendarKnowledgeBindingView annotationBinding =
                inContext(context, () -> bindings.bindAnnotation(
                        "bind-annotation-once",
                        annotation.getId(),
                        CalendarKnowledgeTarget.plan(fixture.planId())));

        assertThat(replay).isEqualTo(factBinding);
        assertThat(factBinding.sourceKind())
                .isEqualTo(CalendarKnowledgeBindingView.SourceKind.FACT);
        assertThat(annotationBinding.sourceKind())
                .isEqualTo(CalendarKnowledgeBindingView.SourceKind.ANNOTATION);
        assertThat(count("calendar_knowledge_fact_binding")).isEqualTo(1L);
        assertThat(count("calendar_knowledge_annotation_binding")).isEqualTo(1L);
    }

    @Test
    void updatingKnowledgeMarksBindingForReviewWithoutChangingCalendar() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "知識更新者");
        WorkspaceContext context = context(actor, workspace);
        Fixture fixture = inContext(context, () -> fixture("轉乘"));
        UserKnowledgeFact fact = inContext(context, () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "轉乘緩衝",
                "至少二十分鐘"));
        inContext(context, () -> bindings.bindFact(
                "bind-updatable-fact",
                fact.getId(),
                CalendarKnowledgeTarget.plan(fixture.planId())));
        var calendarBefore = calendarRouteState(fixture);

        inContext(context, () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "轉乘緩衝",
                "至少四十分鐘"));

        CalendarKnowledgeBindingView binding =
                inContext(context, () -> bindings.getFactBinding(fact.getId(), fixture.planId()));
        assertThat(binding.reviewState())
                .isEqualTo(CalendarKnowledgeBindingView.ReviewState.REVIEW_REQUIRED);
        assertThat(binding.revision()).isEqualTo(2L);
        assertThat(count("calendar_plan")).isEqualTo(1L);
        assertThat(count("calendar_time_node")).isEqualTo(1L);
        assertThat(calendarRouteState(fixture)).isEqualTo(calendarBefore);
    }

    @Test
    void sameWorkspacePeerCannotReadOrBindOwnersPrivateKnowledge() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "知識擁有者");
        seedUser(peer, "同 workspace 其他 actor");
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, () -> fixture("私人行程"));
        UserKnowledgeFact fact = inContext(ownerContext, () -> facts.remember(
                UserKnowledgeFact.Category.INTERPRETATION_PREFERENCE,
                "私人備註",
                "不要分享"));

        assertThatThrownBy(() -> inContext(
                        context(peer, workspace),
                        () -> bindings.bindFact(
                                "peer-cannot-bind",
                                fact.getId(),
                                CalendarKnowledgeTarget.plan(fixture.planId()))))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_knowledge_fact_binding")).isZero();
    }

    @Test
    void intentTargetResolutionIsActorScopedAndRejectsAmbiguousTitles() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "目標擁有者");
        seedUser(peer, "同 workspace 其他使用者");
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture unique = inContext(ownerContext, () -> fixture("唯一日本旅行"));

        var ownerResult = inContext(
                ownerContext,
                () -> intentTargets.resolve("唯一日本旅行", "NODE", "主要節點"));
        var peerResult = inContext(
                context(peer, workspace),
                () -> intentTargets.resolve("唯一日本旅行", "NODE", "主要節點"));

        assertThat(ownerResult)
                .contains(CalendarKnowledgeTarget.node(unique.planId(), unique.nodeId()));
        assertThat(peerResult).isEmpty();

        inContext(ownerContext, () -> fixture("同名旅行"));
        inContext(ownerContext, () -> fixture("同名旅行"));

        assertThat(inContext(
                        ownerContext,
                        () -> intentTargets.resolve("同名旅行", "PLAN", null)))
                .isEmpty();
    }

    @Test
    void databaseCompositeFkRejectsCrossActorKnowledgeAndCalendarComposition() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "行程擁有者");
        seedUser(peer, "知識擁有者");
        Fixture fixture =
                inContext(context(owner, workspace), () -> fixture("不能跨人綁定"));
        UserKnowledgeFact peerFact = inContext(context(peer, workspace), () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "別人的知識",
                "不可綁到擁有者行程"));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO calendar_knowledge_fact_binding (
                            id, fact_id, target_kind, plan_id,
                            source_updated_at, review_state, status, binding_revision,
                            creation_request_hash, creation_payload_hash,
                            created_at, updated_at, workspace_id, created_by_user_id)
                        VALUES (?, ?, 'PLAN', ?, ?, 'CURRENT', 'ACTIVE', 1,
                                ?, ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        peerFact.getId(),
                        fixture.planId(),
                        NOW,
                        "a".repeat(64),
                        "b".repeat(64),
                        NOW,
                        NOW,
                        workspace,
                        owner))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void archivingAnnotationArchivesBindingWithoutDeletingCalendar() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "封存知識");
        WorkspaceContext context = context(actor, workspace);
        Fixture fixture = inContext(context, () -> fixture("保留的行程"));
        ObjectAnnotation annotation = inContext(context, () -> annotations.saveAndFlush(
                ObjectAnnotation.create(
                        ObjectAnnotation.TargetType.MEDIA,
                        92L,
                        "會議附件註記",
                        "會前閱讀",
                        NOW)));
        inContext(context, () -> bindings.bindAnnotation(
                "archive-annotation-binding",
                annotation.getId(),
                CalendarKnowledgeTarget.plan(fixture.planId())));

        inContext(context, () -> {
            Instant archivedAt = NOW.plusSeconds(60);
            annotation.archive(archivedAt);
            annotations.saveAndFlush(annotation);
            events.publishEvent(
                    new com.aproject.aidriven.mymobilesecretary.knowledge.application
                            .ObjectAnnotationArchivedEvent(
                            annotation.getId(), annotation.getSubject(), archivedAt));
            return null;
        });

        CalendarKnowledgeBindingView binding = inContext(
                context,
                () -> bindings.getAnnotationBinding(annotation.getId(), fixture.planId()));
        assertThat(binding.status())
                .isEqualTo(CalendarKnowledgeBindingView.Status.ARCHIVED);
        assertThat(binding.revision()).isEqualTo(2L);
        assertThat(count("calendar_plan")).isEqualTo(1L);
        assertThat(count("calendar_time_node")).isEqualTo(1L);
    }

    @Test
    void archivedPlanRejectsNewKnowledgeBinding() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "已封存知識");
        WorkspaceContext context = context(actor, workspace);
        Fixture fixture = inContext(context, () -> fixture("已封存計畫"));
        UserKnowledgeFact fact = inContext(context, () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "封存後資訊",
                "不應建立新綁定"));
        inContext(context, () -> lifecycle.archive(fixture.planId(), 1));

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> bindings.bindFact(
                                "archived-plan-binding",
                                fact.getId(),
                                CalendarKnowledgeTarget.plan(fixture.planId()))))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("CALENDAR_PLAN_NOT_ACTIVE");
        assertThat(count("calendar_knowledge_fact_binding")).isZero();
    }

    private Fixture fixture(String title) {
        UUID planId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.interval(
                        NOW.plusSeconds(3600), NOW.plusSeconds(7200), TAIPEI),
                NOW));
        nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                nodeId,
                planId,
                null,
                CalendarTimeNode.absolute("primary", "主要節點", NOW.plusSeconds(3600)),
                NOW));
        return new Fixture(planId, nodeId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private java.util.Map<String, Object> calendarRouteState(Fixture fixture) {
        return jdbc.queryForMap(
                """
                SELECT plan.title, plan.placement_kind,
                       plan.timed_start, plan.timed_end, plan.zone_id,
                       plan.version AS plan_version, plan.status,
                       node.node_key, node.label, node.expression_kind,
                       node.absolute_time, node.revision,
                       node.version AS node_version
                FROM calendar_plan plan
                JOIN calendar_time_node node ON node.plan_id = plan.id
                WHERE plan.id = ? AND node.id = ?
                """,
                fixture.planId(),
                fixture.nodeId());
    }

    private void seed(UUID actorId, UUID workspaceId, String label) {
        seedUser(actorId, label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspaceId,
                label,
                actorId);
    }

    private void seedUser(UUID actorId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
    }

    private static WorkspaceContext context(UUID actorId, UUID workspaceId) {
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record Fixture(UUID planId, UUID nodeId) {}
}
