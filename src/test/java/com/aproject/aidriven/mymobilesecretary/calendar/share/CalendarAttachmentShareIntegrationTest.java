package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.attachment.CalendarAttachmentBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.attachment.CalendarAttachmentTarget;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarAttachmentShareIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-25T06:00:00Z");
    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    @Autowired private CalendarShareService shares;
    @Autowired private CalendarShareContentGrantService grants;
    @Autowired private CalendarAttachmentBindingService bindings;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private MediaStorageService media;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void attachmentBytesRequireIndependentGrantAndUnlinkRevokesImmediately() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, recipient, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);

        Fixture fixture = inContext(ownerContext, () -> {
            UUID planId = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    planId,
                    "船票附件",
                    CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            StoredMedia stored = media.store(
                    StoredMedia.SourceType.APP,
                    "calendar-share-ticket",
                    "船票",
                    "ticket.png",
                    "application/octet-stream",
                    PNG);
            var binding = bindings.bind(
                    "bind-ticket",
                    stored.getId(),
                    CalendarAttachmentTarget.plan(planId),
                    "船票",
                    0);
            return new Fixture(planId, binding.id());
        });
        CalendarShareView share = inContext(ownerContext, () -> shares.createViewerShare(
                "share-ticket-plan", fixture.planId(), recipient, 1));

        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readAttachment(UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        CalendarContentGrantView grant = inContext(ownerContext, () -> grants.grantAttachment(
                "grant-ticket", share.id(), fixture.bindingId()));

        SharedAttachmentContent content = inContext(
                context(recipient, workspace),
                () -> grants.readAttachment(grant.id()));
        assertThat(content.displayName()).isEqualTo("船票");
        assertThat(content.mediaType()).isEqualTo("image/png");
        assertThat(content.image()).isTrue();
        assertThat(content.bytes()).containsExactly(PNG);

        inContext(ownerContext, () -> bindings.unlink(fixture.bindingId(), 1));
        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readAttachment(grant.id())))
                .isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_content_grant
                        WHERE id = ? AND status = 'REVOKED'
                        """,
                        Long.class,
                        grant.id()))
                .isEqualTo(1L);
        assertThat(count("stored_media")).isEqualTo(1L);
        assertThat(count("calendar_attachment_binding")).isEqualTo(1L);
    }

    @Test
    void revokingOneRecipientKeepsOtherRecipientAndOwnerContentIntact() {
        UUID owner = UUID.randomUUID();
        UUID recipientA = UUID.randomUUID();
        UUID recipientB = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, recipientA, workspace);
        seedUser(recipientB, "附件收件者 B");
        addMember(workspace, recipientB, owner, "MEMBER");
        WorkspaceContext ownerContext = context(owner, workspace);
        Fixture fixture = inContext(ownerContext, () -> {
            UUID planId = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    planId,
                    "雙收件者船票",
                    CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            StoredMedia stored = media.store(
                    StoredMedia.SourceType.APP,
                    "calendar-share-two-recipients",
                    "雙人船票",
                    "ticket.png",
                    "application/octet-stream",
                    PNG);
            var binding = bindings.bind(
                    "bind-two-recipient-ticket",
                    stored.getId(),
                    CalendarAttachmentTarget.plan(planId),
                    "雙人船票",
                    0);
            return new Fixture(planId, binding.id());
        });
        CalendarShareView shareA = inContext(ownerContext, () -> shares.createViewerShare(
                "share-ticket-a", fixture.planId(), recipientA, 1));
        CalendarShareView shareB = inContext(ownerContext, () -> shares.createViewerShare(
                "share-ticket-b", fixture.planId(), recipientB, 1));
        CalendarContentGrantView grantA = inContext(ownerContext, () -> grants.grantAttachment(
                "grant-ticket-a", shareA.id(), fixture.bindingId()));
        CalendarContentGrantView grantB = inContext(ownerContext, () -> grants.grantAttachment(
                "grant-ticket-b", shareB.id(), fixture.bindingId()));

        assertThat(inContext(
                                context(recipientA, workspace),
                                () -> grants.readAttachment(grantA.id()))
                        .bytes())
                .containsExactly(PNG);
        assertThat(inContext(
                                context(recipientB, workspace),
                                () -> grants.readAttachment(grantB.id()))
                        .bytes())
                .containsExactly(PNG);

        inContext(ownerContext, () -> grants.revoke(
                "revoke-ticket-a", grantA.id(), grantA.revision()));

        assertThatThrownBy(() -> inContext(
                        context(recipientA, workspace),
                        () -> grants.readAttachment(grantA.id())))
                .isInstanceOf(NotFoundException.class);
        assertThat(inContext(
                                context(recipientB, workspace),
                                () -> grants.readAttachment(grantB.id()))
                        .bytes())
                .containsExactly(PNG);
        assertThat(inContext(
                        ownerContext,
                        () -> bindings.get(fixture.bindingId()).status()))
                .isEqualTo(
                        com.aproject.aidriven.mymobilesecretary.calendar.attachment
                                .CalendarAttachmentBindingView.Status.ACTIVE);
        assertThat(count("stored_media")).isEqualTo(1L);
        assertThat(count("calendar_attachment_binding")).isEqualTo(1L);
    }

    @Test
    void replacementIsImmutableReplayableAndPreservesGrantIdentity() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, recipient, workspace);
        WorkspaceContext ownerContext = context(owner, workspace);
        ReplacementFixture fixture = inContext(ownerContext, () -> {
            UUID planId = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    planId,
                    "新版船票",
                    CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            StoredMedia oldMedia = media.store(
                    StoredMedia.SourceType.APP,
                    "old-ticket",
                    "舊船票",
                    "old-ticket.png",
                    "application/octet-stream",
                    PNG);
            StoredMedia newMedia = media.store(
                    StoredMedia.SourceType.APP,
                    "new-ticket",
                    "新版船票",
                    "new-ticket.png",
                    "application/octet-stream",
                    PNG);
            StoredMedia finalMedia = media.store(
                    StoredMedia.SourceType.APP,
                    "final-ticket",
                    "最終版船票",
                    "final-ticket.png",
                    "application/octet-stream",
                    PNG);
            var binding = bindings.bind(
                    "bind-old-ticket",
                    oldMedia.getId(),
                    CalendarAttachmentTarget.plan(planId),
                    "船票",
                    0);
            return new ReplacementFixture(
                    planId,
                    binding.id(),
                    oldMedia.getId(),
                    newMedia.getId(),
                    finalMedia.getId());
        });
        CalendarShareView share = inContext(ownerContext, () -> shares.createViewerShare(
                "share-replace-ticket", fixture.planId(), recipient, 1));
        CalendarContentGrantView oldGrant = inContext(ownerContext, () -> grants.grantAttachment(
                "grant-old-ticket", share.id(), fixture.bindingId()));
        assertThat(inContext(
                                context(recipient, workspace),
                                () -> grants.readAttachment(oldGrant.id()))
                        .bytes())
                .containsExactly(PNG);

        var firstReplacement = inContext(ownerContext, () -> bindings.replace(
                "replace-ticket-first",
                fixture.bindingId(),
                fixture.newMediaId(),
                1));
        var firstReplayBeforeNextReplacement = inContext(ownerContext, () -> bindings.replace(
                "replace-ticket-first",
                fixture.bindingId(),
                fixture.newMediaId(),
                1));

        assertThat(firstReplacement.id()).isNotEqualTo(fixture.bindingId());
        assertThat(firstReplacement.mediaId()).isEqualTo(fixture.newMediaId());
        assertThat(firstReplacement.revision()).isEqualTo(2);
        assertThat(firstReplayBeforeNextReplacement).isEqualTo(firstReplacement);
        assertThatThrownBy(() -> inContext(ownerContext, () -> bindings.replace(
                        "replace-ticket-first",
                        fixture.bindingId(),
                        fixture.finalMediaId(),
                        1)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        failure -> assertThat(failure.getCode())
                                .isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThat(inContext(ownerContext, () -> bindings.get(fixture.bindingId())))
                .satisfies(oldBinding -> {
                    assertThat(oldBinding.mediaId()).isEqualTo(fixture.oldMediaId());
                    assertThat(oldBinding.status())
                            .isEqualTo(
                                    com.aproject.aidriven.mymobilesecretary.calendar
                                            .attachment.CalendarAttachmentBindingView.Status
                                            .REPLACED);
                    assertThat(oldBinding.revision()).isEqualTo(2);
                });
        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readAttachment(oldGrant.id())))
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> jdbc.update(
                        """
                        UPDATE calendar_share_content_grant
                        SET media_id = ?
                        WHERE id = ?
                        """,
                        fixture.newMediaId(),
                        oldGrant.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_calendar_content_grant_attachment");

        CalendarContentGrantView firstReplacementGrant =
                inContext(ownerContext, () -> grants.grantAttachment(
                        "grant-first-replacement",
                        share.id(),
                        firstReplacement.id()));
        assertThat(inContext(
                                context(recipient, workspace),
                                () -> grants.readAttachment(
                                        firstReplacementGrant.id()))
                        .bytes())
                .containsExactly(PNG);

        var secondReplacement = inContext(ownerContext, () -> bindings.replace(
                "replace-ticket-second",
                firstReplacement.id(),
                fixture.finalMediaId(),
                2));
        var firstReplayAfterSecondReplacement = inContext(ownerContext, () -> bindings.replace(
                "replace-ticket-first",
                fixture.bindingId(),
                fixture.newMediaId(),
                1));
        var secondReplay = inContext(ownerContext, () -> bindings.replace(
                "replace-ticket-second",
                firstReplacement.id(),
                fixture.finalMediaId(),
                2));

        assertThat(secondReplacement.id())
                .isNotEqualTo(firstReplacement.id())
                .isNotEqualTo(fixture.bindingId());
        assertThat(secondReplacement.mediaId()).isEqualTo(fixture.finalMediaId());
        assertThat(secondReplacement.revision()).isEqualTo(3);
        assertThat(firstReplayAfterSecondReplacement.id())
                .isEqualTo(firstReplacement.id());
        assertThat(firstReplayAfterSecondReplacement.status())
                .isEqualTo(
                        com.aproject.aidriven.mymobilesecretary.calendar.attachment
                                .CalendarAttachmentBindingView.Status.REPLACED);
        assertThat(secondReplay).isEqualTo(secondReplacement);
        assertThatThrownBy(() -> inContext(
                        context(recipient, workspace),
                        () -> grants.readAttachment(firstReplacementGrant.id())))
                .isInstanceOf(NotFoundException.class);

        CalendarContentGrantView finalGrant =
                inContext(ownerContext, () -> grants.grantAttachment(
                        "grant-final-replacement",
                        share.id(),
                        secondReplacement.id()));
        assertThat(inContext(
                                context(recipient, workspace),
                                () -> grants.readAttachment(finalGrant.id()))
                        .bytes())
                .containsExactly(PNG);

        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_share_content_grant
                        WHERE status = 'REVOKED'
                          AND (
                            (attachment_binding_id = ? AND media_id = ?)
                            OR (attachment_binding_id = ? AND media_id = ?))
                        """,
                        Long.class,
                        fixture.bindingId(),
                        fixture.oldMediaId(),
                        firstReplacement.id(),
                        fixture.newMediaId()))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_attachment_replacement
                        WHERE workspace_id = ? AND created_by_user_id = ?
                        """,
                        Long.class,
                        workspace,
                        owner))
                .isEqualTo(2L);
        UUID replacementLedgerId = jdbc.queryForObject(
                """
                SELECT id
                FROM calendar_attachment_replacement
                WHERE workspace_id = ? AND created_by_user_id = ?
                ORDER BY created_at, id
                LIMIT 1
                """,
                UUID.class,
                workspace,
                owner);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        UPDATE calendar_attachment_replacement
                        SET payload_hash = payload_hash
                        WHERE id = ?
                        """,
                        replacementLedgerId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "calendar_attachment_replacement is append-only");
        assertThatThrownBy(() -> jdbc.update(
                        """
                        DELETE FROM calendar_attachment_replacement
                        WHERE id = ?
                        """,
                        replacementLedgerId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(
                        "calendar_attachment_replacement is append-only");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_share_outbox
                        WHERE event_type = 'CONTENT_REVOKED'
                          AND content_grant_id IN (?, ?)
                          AND payload_text =
                            'Calendar content access revoked: ATTACHMENT_REPLACED'
                        """,
                        Long.class,
                        oldGrant.id(),
                        firstReplacementGrant.id()))
                .isEqualTo(2L);
        assertThat(inContext(ownerContext, media::listRecent))
                .filteredOn(stored -> stored.getId().equals(fixture.oldMediaId())
                        || stored.getId().equals(fixture.newMediaId())
                        || stored.getId().equals(fixture.finalMediaId()))
                .allSatisfy(stored -> assertThat(stored.getStatus())
                        .isEqualTo(StoredMedia.Status.AVAILABLE))
                .hasSize(3);
        assertThat(count("calendar_attachment_binding")).isEqualTo(3L);
    }

    private void seed(UUID owner, UUID recipient, UUID workspace) {
        seedUser(owner, "附件擁有者");
        seedUser(recipient, "附件收件者");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, '附件家庭', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
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

    private record Fixture(UUID planId, UUID bindingId) {}

    private record ReplacementFixture(
            UUID planId,
            UUID bindingId,
            long oldMediaId,
            long newMediaId,
            long finalMediaId) {}
}
