package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarPlanLifecycleService;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import com.aproject.aidriven.mymobilesecretary.media.persistence.StoredMediaRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CalendarAttachmentBindingIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-24T11:00:00Z");

    @Autowired private CalendarAttachmentBindingService bindings;
    @Autowired private CalendarPlanLifecycleService lifecycle;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private StoredMediaRepository media;
    @Autowired private MediaStorageService mediaStorage;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void bindingIsActorPrivateIdempotentAndDoesNotGrantContent() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "附件擁有者");
        WorkspaceContext context = context(actor, workspace);
        Fixture fixture = inContext(context, () -> fixture("登船附件"));

        CalendarAttachmentBindingView created = inContext(context, () -> bindings.bind(
                "bind-media-once",
                fixture.mediaId(),
                CalendarAttachmentTarget.plan(fixture.planId()),
                "船票",
                0));
        CalendarAttachmentBindingView replay = inContext(context, () -> bindings.bind(
                "bind-media-once",
                fixture.mediaId(),
                CalendarAttachmentTarget.plan(fixture.planId()),
                "船票",
                0));

        assertThat(replay).isEqualTo(created);
        assertThat(created.status()).isEqualTo(CalendarAttachmentBindingView.Status.ACTIVE);
        assertThat(created).hasNoNullFieldsOrProperties();
        assertThat(count("calendar_attachment_binding")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM calendar_share_content_grant "
                                + "WHERE workspace_id = ?",
                        Long.class,
                        workspace))
                .isZero();
    }

    @Test
    void unlinkAndMediaDeleteHaveSeparateLifecycleAndNeverDeleteCalendar() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "附件生命週期");
        WorkspaceContext context = context(actor, workspace);
        Fixture first = inContext(context, () -> fixture("解除綁定"));
        CalendarAttachmentBindingView firstBinding = inContext(context, () -> bindings.bind(
                "unlink-media",
                first.mediaId(),
                CalendarAttachmentTarget.plan(first.planId()),
                "附件一",
                0));

        inContext(context, () -> bindings.unlink(firstBinding.id(), 1));

        assertThat(inContext(context, () -> media.findById(first.mediaId()).orElseThrow())
                        .getStatus())
                .isEqualTo(StoredMedia.Status.AVAILABLE);
        assertThat(inContext(context, () -> bindings.get(firstBinding.id())).status())
                .isEqualTo(CalendarAttachmentBindingView.Status.UNLINKED);

        Fixture second = inContext(context, () -> fixture("媒體刪除"));
        CalendarAttachmentBindingView secondBinding = inContext(context, () -> bindings.bind(
                "deleted-media",
                second.mediaId(),
                CalendarAttachmentTarget.plan(second.planId()),
                "附件二",
                0));
        inContext(context, () -> {
            mediaStorage.delete(second.mediaId());
            return null;
        });

        assertThat(inContext(context, () -> media.findById(second.mediaId()).orElseThrow())
                        .getStatus())
                .isEqualTo(StoredMedia.Status.DELETED);
        assertThat(inContext(context, () -> bindings.get(secondBinding.id())).status())
                .isEqualTo(CalendarAttachmentBindingView.Status.MEDIA_DELETED);
        assertThat(count("calendar_plan")).isEqualTo(2L);
    }

    @Test
    void sameWorkspacePeerCannotBindOwnersMediaOrCalendar() {
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(owner, workspace, "附件 owner");
        seedUser(peer, "同 workspace peer");
        Fixture fixture = inContext(context(owner, workspace), () -> fixture("私人附件"));

        assertThatThrownBy(() -> inContext(
                        context(peer, workspace),
                        () -> bindings.bind(
                                "peer-bind",
                                fixture.mediaId(),
                                CalendarAttachmentTarget.plan(fixture.planId()),
                                "猜測附件",
                                0)))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_attachment_binding")).isZero();
    }

    @Test
    void canceledPlanRejectsNewAttachmentBinding() {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seed(actor, workspace, "已取消附件");
        WorkspaceContext context = context(actor, workspace);
        Fixture fixture = inContext(context, () -> fixture("已取消計畫"));
        inContext(context, () -> lifecycle.cancel(fixture.planId(), 1));

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> bindings.bind(
                                "canceled-plan-binding",
                                fixture.mediaId(),
                                CalendarAttachmentTarget.plan(fixture.planId()),
                                "不應綁定",
                                0)))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("CALENDAR_PLAN_NOT_ACTIVE");
        assertThat(count("calendar_attachment_binding")).isZero();
    }

    private Fixture fixture(String title) {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        StoredMedia stored = media.saveAndFlush(StoredMedia.create(
                StoredMedia.SourceType.APP,
                StoredMedia.MediaKind.DOCUMENT,
                title + ".pdf",
                title + ".pdf",
                "application/pdf",
                10,
                "a".repeat(64),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                NOW));
        return new Fixture(planId, stored.getId());
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

    private record Fixture(UUID planId, long mediaId) {}
}
