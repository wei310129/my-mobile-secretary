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
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarKnowledgeExcerptIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarKnowledgeExcerptService excerpts;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private UserKnowledgeService facts;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void onlyExplicitlyApprovedImmutableSnapshotBecomesGrantEligible() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "摘錄擁有者");
        WorkspaceContext context = context(actor, workspace);
        Source source = inContext(context, () -> source("登船", "至少提早四十分鐘"));

        CalendarKnowledgeExcerptView draft = inContext(context, () -> excerpts.createFactDraft(
                "excerpt-draft-once",
                source.bindingId(),
                "登船提醒",
                "至少提早四十分鐘"));

        assertThat(draft.status()).isEqualTo(CalendarKnowledgeExcerptView.Status.DRAFT);
        assertThat(inContext(context, () -> excerpts.listGrantEligible(source.planId())))
                .isEmpty();

        CalendarKnowledgeExcerptView approved =
                inContext(context, () -> excerpts.approve(draft.id(), 1));

        assertThat(approved.status())
                .isEqualTo(CalendarKnowledgeExcerptView.Status.APPROVED);
        assertThat(approved.versionNumber()).isEqualTo(1);
        assertThat(inContext(context, () -> excerpts.listGrantEligible(source.planId())))
                .containsExactly(approved);
    }

    @Test
    void sourceUpdateKeepsApprovedSnapshotButRequiresReview() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "摘錄更新者");
        WorkspaceContext context = context(actor, workspace);
        Source source = inContext(context, () -> source("轉乘", "至少二十分鐘"));
        CalendarKnowledgeExcerptView draft = inContext(context, () -> excerpts.createFactDraft(
                "reviewable-excerpt",
                source.bindingId(),
                "轉乘緩衝",
                "至少二十分鐘"));
        inContext(context, () -> excerpts.approve(draft.id(), 1));

        inContext(context, () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "轉乘",
                "至少四十分鐘"));

        CalendarKnowledgeExcerptView reviewed =
                inContext(context, () -> excerpts.get(draft.id()));
        assertThat(reviewed.status())
                .isEqualTo(CalendarKnowledgeExcerptView.Status.REVIEW_REQUIRED);
        assertThat(reviewed.snapshotText()).isEqualTo("至少二十分鐘");
        assertThat(inContext(context, () -> excerpts.listGrantEligible(source.planId())))
                .isEmpty();
        assertThat(count("calendar_plan")).isEqualTo(1L);
    }

    @Test
    void sourceUpdateBeforeApprovalMakesOldDraftUnapprovable() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "舊草稿不可批准");
        WorkspaceContext context = context(actor, workspace);
        Source source = inContext(context, () -> source("接駁", "至少二十分鐘"));
        CalendarKnowledgeExcerptView draft = inContext(context, () -> excerpts.createFactDraft(
                "stale-draft",
                source.bindingId(),
                "接駁提醒",
                "至少二十分鐘"));

        inContext(context, () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "接駁",
                "至少四十分鐘"));

        assertThatThrownBy(() -> inContext(
                        context, () -> excerpts.approve(draft.id(), draft.revision())))
                .isInstanceOf(
                        com.aproject.aidriven.mymobilesecretary.shared.error
                                .BusinessException.class)
                .hasMessageContaining("current");
        assertThat(inContext(context, () -> excerpts.listGrantEligible(source.planId())))
                .isEmpty();
    }

    @Test
    void peerCannotReadExcerptAndRevokeDoesNotDeleteKnowledgeOrCalendar() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "摘錄 owner");
        seedUser(peer, "同 workspace peer");
        WorkspaceContext ownerContext = context(owner, workspace);
        Source source = inContext(ownerContext, () -> source("私人行程", "私人內容"));
        CalendarKnowledgeExcerptView draft = inContext(ownerContext, () -> excerpts.createFactDraft(
                "private-excerpt",
                source.bindingId(),
                "私人摘錄",
                "只分享這一句"));
        CalendarKnowledgeExcerptView approved =
                inContext(ownerContext, () -> excerpts.approve(draft.id(), 1));

        assertThatThrownBy(() -> inContext(
                        context(peer, workspace),
                        () -> excerpts.get(approved.id())))
                .isInstanceOf(NotFoundException.class);

        CalendarKnowledgeExcerptView revoked =
                inContext(ownerContext, () -> excerpts.revoke(approved.id(), 2));
        assertThat(revoked.status()).isEqualTo(CalendarKnowledgeExcerptView.Status.REVOKED);
        assertThat(count("user_knowledge_fact")).isEqualTo(1L);
        assertThat(count("calendar_plan")).isEqualTo(1L);
    }

    private Source source(String title, String detail) {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        UserKnowledgeFact fact = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE, title, detail);
        CalendarKnowledgeBindingView binding = bindings.bindFact(
                "binding-" + planId,
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        return new Source(planId, binding.id());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
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

    private record Source(UUID planId, UUID bindingId) {}
}
