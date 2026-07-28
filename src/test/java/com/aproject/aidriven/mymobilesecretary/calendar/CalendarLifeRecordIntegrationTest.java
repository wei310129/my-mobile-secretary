package com.aproject.aidriven.mymobilesecretary.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarNodeDraft;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CreateCalendarPlanCommand;
import com.aproject.aidriven.mymobilesecretary.calendar.attachment.CalendarAttachmentBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.attachment.CalendarAttachmentTarget;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeExcerptService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeReadService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareContentGrantService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarLifeRecordIntegrationTest extends IntegrationTestBase {

    private static final Instant START = Instant.parse("2026-07-25T10:00:00Z");

    @Autowired private CalendarApplicationService calendars;
    @Autowired private CalendarKnowledgeBindingService knowledgeBindings;
    @Autowired private CalendarKnowledgeExcerptService excerpts;
    @Autowired private CalendarKnowledgeReadService reads;
    @Autowired private CalendarAttachmentBindingService attachments;
    @Autowired private CalendarShareService shares;
    @Autowired private CalendarShareContentGrantService grants;
    @Autowired private UserKnowledgeService knowledge;
    @Autowired private MediaStorageService media;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void planAndStandaloneNodeRecordExactlyOneDomainEventEach() {
        WorkspaceContext context = seedContext("calendar life owner");
        CreateCalendarPlanCommand command = command(
                "life-plan-create", "生活事件行程", "initial", "初始節點");

        var created =
                inContext(context, () -> calendars.createPlanWithIdentity(command));
        var replay =
                inContext(context, () -> calendars.createPlanWithIdentity(command));

        assertThat(replay).isEqualTo(created);
        assertThat(sourceCount(
                        context,
                        "calendar-plan/" + created.planId() + "/created"))
                .isEqualTo(1L);
        assertThat(scheduleCount(context)).isEqualTo(1L);

        inContext(context, () -> calendars.addAbsoluteNode(
                created.planId(),
                "materialized",
                "新增節點",
                START.plusSeconds(1800)));
        UUID nodeId = jdbc.queryForObject(
                """
                SELECT id FROM calendar_time_node
                WHERE plan_id = ? AND node_key = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                UUID.class,
                created.planId(),
                "materialized",
                context.workspaceId(),
                context.actorId());

        String nodeSource = "calendar-node/" + nodeId + "/created";
        assertThat(sourceCount(context, nodeSource)).isEqualTo(1L);
        assertThat(bindingCount(context, nodeSource)).isGreaterThan(0L);
        assertThat(scheduleCount(context)).isEqualTo(2L);
    }

    @Test
    void outerTransactionRollbackRemovesCalendarAndLifeRecordTogether() {
        WorkspaceContext context = seedContext("calendar rollback owner");
        CreateCalendarPlanCommand command = command(
                "life-plan-rollback", "整體回滾行程", "anchor", "回滾節點");

        inContext(context, () -> new TransactionTemplate(transactions)
                .execute(status -> {
                    calendars.createPlan(command);
                    status.setRollbackOnly();
                    return null;
                }));

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_plan
                        WHERE title = ? AND workspace_id = ?
                          AND created_by_user_id = ?
                        """,
                        Long.class,
                        "整體回滾行程",
                        context.workspaceId(),
                        context.actorId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM tagged_life_record
                        WHERE title = ? AND workspace_id = ?
                          AND created_by_user_id = ?
                        """,
                        Long.class,
                        "整體回滾行程",
                        context.workspaceId(),
                        context.actorId()))
                .isZero();
    }

    @Test
    void knowledgeAttachmentAndShareViewsDoNotCreateLifeRecords() {
        WorkspaceContext ownerContext = seedContext("calendar no-event owner");
        UUID recipient = UUID.randomUUID();
        seedUserAndMembership(
                recipient, ownerContext, "calendar no-event recipient");
        Fixture fixture = inContext(ownerContext, () -> {
            UserKnowledgeFact fact = knowledge.remember(
                    UserKnowledgeFact.Category.PLACE_GUIDANCE,
                    "船票規則",
                    "登船前確認證件");
            var plan = calendars.createPlanWithIdentity(command(
                    "no-event-plan", "不記錄純操作", "anchor", "登船"));
            StoredMedia stored = media.store(
                    StoredMedia.SourceType.APP,
                    "no-event-media",
                    "船票",
                    "ticket.png",
                    "application/octet-stream",
                    new byte[] {
                        (byte) 0x89,
                        0x50,
                        0x4e,
                        0x47,
                        0x0d,
                        0x0a,
                        0x1a,
                        0x0a
                    });
            return new Fixture(plan.planId(), fact.getId(), stored.getId());
        });
        long baseline = lifeCount(ownerContext);

        var binding = inContext(ownerContext, () -> knowledgeBindings.bindFact(
                "no-event-fact-binding",
                fixture.factId(),
                CalendarKnowledgeTarget.plan(fixture.planId())));
        assertThat(lifeCount(ownerContext)).isEqualTo(baseline);
        assertThat(inContext(ownerContext, () -> reads.retrieve(
                        CalendarKnowledgeTarget.plan(fixture.planId()),
                        "船票",
                        5)))
                .hasSize(1);
        assertThat(lifeCount(ownerContext)).isEqualTo(baseline);
        var excerpt = inContext(ownerContext, () -> excerpts.createFactDraft(
                "no-event-excerpt", binding.id(), "船票摘要", "確認證件"));
        var approved =
                inContext(ownerContext, () -> excerpts.approve(excerpt.id(), 1));
        assertThat(lifeCount(ownerContext)).isEqualTo(baseline);
        var attachment = inContext(ownerContext, () -> attachments.bind(
                "no-event-attachment",
                fixture.mediaId(),
                CalendarAttachmentTarget.plan(fixture.planId()),
                "船票",
                0));
        assertThat(lifeCount(ownerContext)).isEqualTo(baseline);
        var share = inContext(ownerContext, () -> shares.createViewerShare(
                "no-event-share", fixture.planId(), recipient, 1));
        var excerptGrant = inContext(ownerContext, () -> grants.grantKnowledgeExcerpt(
                "no-event-excerpt-grant", share.id(), approved.id()));
        var attachmentGrant = inContext(ownerContext, () -> grants.grantAttachment(
                "no-event-attachment-grant", share.id(), attachment.id()));
        assertThat(lifeCount(ownerContext)).isEqualTo(baseline);

        WorkspaceContext recipientContext =
                new WorkspaceContext(
                        recipient,
                        ownerContext.workspaceId(),
                        WorkspaceChannel.TEST);
        assertThat(inContext(recipientContext, () -> grants
                                .readKnowledgeExcerpt(excerptGrant.id())
                                .text()))
                .isEqualTo("確認證件");
        assertThat(inContext(recipientContext, () -> grants
                                .readAttachment(attachmentGrant.id())
                                .displayName()))
                .isEqualTo("船票");
        assertThat(lifeCount(ownerContext)).isEqualTo(baseline);
    }

    private CreateCalendarPlanCommand command(
            String requestKey, String title, String nodeKey, String nodeLabel) {
        return new CreateCalendarPlanCommand(
                requestKey,
                title,
                CalendarPlacement.point(START, ZoneId.of("Asia/Taipei")),
                "測試",
                null,
                null,
                List.of(),
                List.of(CalendarNodeDraft.of(
                        CalendarTimeNode.absolute(nodeKey, nodeLabel, START))));
    }

    private long sourceCount(WorkspaceContext context, String sourceIdentity) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM tagged_life_record
                WHERE source_event_key_hash = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                sha256(sourceIdentity),
                context.workspaceId(),
                context.actorId());
    }

    private long bindingCount(WorkspaceContext context, String sourceIdentity) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM semantic_tag_binding binding
                JOIN tagged_life_record life
                  ON life.id = binding.target_id
                 AND life.workspace_id = binding.workspace_id
                 AND life.created_by_user_id = binding.created_by_user_id
                WHERE binding.target_type = 'LIFE_RECORD'
                  AND life.source_event_key_hash = ?
                  AND life.workspace_id = ?
                  AND life.created_by_user_id = ?
                """,
                Long.class,
                sha256(sourceIdentity),
                context.workspaceId(),
                context.actorId());
    }

    private long scheduleCount(WorkspaceContext context) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM tagged_life_record
                WHERE record_type = 'SCHEDULE'
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                context.workspaceId(),
                context.actorId());
    }

    private long lifeCount(WorkspaceContext context) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM tagged_life_record
                WHERE workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                context.workspaceId(),
                context.actorId());
    }

    private void seedUserAndMembership(
            UUID recipient, WorkspaceContext owner, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                recipient,
                label);
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                owner.workspaceId(),
                recipient,
                owner.actorId());
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
                VALUES (?, ?, 'PERSONAL', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                label,
                actor);
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fixture(UUID planId, long factId, long mediaId) {}
}
