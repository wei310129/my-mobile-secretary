package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleService;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeExcerptService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeExcerptView;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
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

class CalendarKnowledgeExcerptShareIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-25T05:00:00Z");

    @Autowired private CalendarShareService shares;
    @Autowired private CalendarShareContentGrantService grants;
    @Autowired private CalendarPlanLifecycleService lifecycle;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarKnowledgeExcerptService excerpts;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private UserKnowledgeService facts;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void coreShareDoesNotExposePrivateKnowledgeAndExplicitGrantIsRevocable() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedHousehold(owner, recipient, peer, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, () -> fixture("大阪登船"));

        CalendarShareView share = inContext(ownerContext, () -> shares.createViewerShare(
                "share-osaka-viewer", fixture.planId(), recipient, 1));

        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readKnowledgeExcerpt(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_share_content_grant")).isZero();

        CalendarContentGrantView grant = inContext(ownerContext, () -> grants.grantKnowledgeExcerpt(
                "grant-boarding-excerpt", share.id(), fixture.excerptId()));
        CalendarContentGrantView replay = inContext(ownerContext, () -> grants.grantKnowledgeExcerpt(
                "grant-boarding-excerpt", share.id(), fixture.excerptId()));

        assertThat(replay).isEqualTo(grant);
        SharedKnowledgeExcerptView visible = inContext(
                context(recipient, workspace),
                () -> grants.readKnowledgeExcerpt(grant.id()));
        assertThat(visible).isEqualTo(
                new SharedKnowledgeExcerptView(1, "登船提醒", "請在開船前四十分鐘抵達。"));
        assertThatThrownBy(() -> inContext(
                        context(peer, workspace),
                        () -> grants.readKnowledgeExcerpt(grant.id())))
                .isInstanceOf(NotFoundException.class);

        CalendarContentGrantView revoked = inContext(
                ownerContext, () -> grants.revoke("revoke-boarding-excerpt", grant.id(), 1));
        assertThat(revoked.status()).isEqualTo(CalendarContentGrantView.Status.REVOKED);
        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readKnowledgeExcerpt(grant.id())))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_plan")).isEqualTo(1L);
        assertThat(count("user_knowledge_fact")).isEqualTo(1L);
        assertThat(count("calendar_knowledge_excerpt")).isEqualTo(1L);
        assertThat(count("calendar_share_outbox")).isEqualTo(3L);
    }

    @Test
    void suspendedOrNonMemberRecipientFailsClosed() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedHousehold(owner, recipient, null, workspace);
        seedUser(outsider, "非成員");
        WorkspaceContext ownerContext = context(owner, workspace);
        UUID planId = inContext(ownerContext, () -> {
            UUID id = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    id,
                    "成員狀態",
                    CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            return id;
        });

        assertThatThrownBy(() -> inContext(
                        ownerContext,
                        () -> shares.createViewerShare(
                                "share-outsider", planId, outsider, 1)))
                .isInstanceOf(NotFoundException.class);
        jdbc.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?", recipient);
        assertThatThrownBy(() -> inContext(
                        ownerContext,
                        () -> shares.createViewerShare(
                                "share-suspended", planId, recipient, 1)))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_share")).isZero();
    }

    @Test
    void revokingShareCascadesOnlyItsContentAccess() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedHousehold(owner, recipient, null, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, () -> fixture("撤銷分享"));
        CalendarShareView share = inContext(ownerContext, () -> shares.createViewerShare(
                "share-revoke-plan", fixture.planId(), recipient, 1));
        CalendarContentGrantView grant = inContext(ownerContext, () -> grants.grantKnowledgeExcerpt(
                "grant-before-share-revoke", share.id(), fixture.excerptId()));

        CalendarShareView revoked =
                inContext(ownerContext, () -> shares.revoke("revoke-whole-share", share.id(), 1));

        assertThat(revoked.status()).isEqualTo(CalendarShareView.Status.REVOKED);
        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readKnowledgeExcerpt(grant.id())))
                .isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_content_grant
                        WHERE id = ? AND status = 'REVOKED'
                        """,
                        Long.class,
                        grant.id()))
                .isEqualTo(1L);
        assertThat(count("calendar_plan")).isEqualTo(1L);
        assertThat(count("user_knowledge_fact")).isEqualTo(1L);
        assertThat(count("calendar_knowledge_excerpt")).isEqualTo(1L);
        assertThat(count("calendar_share_outbox")).isEqualTo(4L);
    }

    @Test
    void cancelingPlanRevokesShareAndGrantWithoutDeletingKnowledge() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedHousehold(owner, recipient, null, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, () -> fixture("取消計畫"));
        CalendarShareView share = inContext(ownerContext, () -> shares.createViewerShare(
                "share-before-plan-cancel", fixture.planId(), recipient, 1));
        CalendarContentGrantView grant = inContext(ownerContext, () -> grants.grantKnowledgeExcerpt(
                "grant-before-plan-cancel", share.id(), fixture.excerptId()));

        inContext(ownerContext, () -> lifecycle.cancel(fixture.planId(), 1));

        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readKnowledgeExcerpt(grant.id())))
                .isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_share WHERE status = 'REVOKED'",
                        Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_content_grant
                        WHERE status = 'REVOKED'
                        """,
                        Long.class))
                .isEqualTo(1L);
        assertThat(count("calendar_plan")).isEqualTo(1L);
        assertThat(count("user_knowledge_fact")).isEqualTo(1L);
        assertThat(count("calendar_knowledge_excerpt")).isEqualTo(1L);
        assertThat(count("calendar_share_outbox")).isEqualTo(4L);
    }

    private Fixture fixture(String title) {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        UserKnowledgeFact fact = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "大阪港",
                "原始私人資料：護照號碼不應分享");
        var binding = bindings.bindFact(
                "bind-osaka-fact",
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        CalendarKnowledgeExcerptView draft = excerpts.createFactDraft(
                "draft-boarding-excerpt",
                binding.id(),
                "登船提醒",
                "請在開船前四十分鐘抵達。");
        CalendarKnowledgeExcerptView approved = excerpts.approve(draft.id(), 1);
        return new Fixture(planId, approved.id());
    }

    private void seedHousehold(
            UUID owner,
            UUID recipient,
            UUID peer,
            UUID workspace) {
        seedUser(owner, "分享擁有者");
        seedUser(recipient, "分享收件者");
        if (peer != null) {
            seedUser(peer, "同 workspace peer");
        }
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, '家庭行事曆', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        if (peer != null) {
            addMember(workspace, peer, owner, "MEMBER");
        }
    }

    private void addMember(
            UUID workspace,
            UUID user,
            UUID creator,
            String role) {
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

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private record Fixture(UUID planId, UUID excerptId) {}
}
