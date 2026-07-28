package com.aproject.aidriven.mymobilesecretary.api.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.attachment.CalendarAttachmentBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.attachment.CalendarAttachmentTarget;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeExcerptService;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareContentGrantService;
import com.aproject.aidriven.mymobilesecretary.calendar.share.CalendarShareService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.media.application.MediaStorageService;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class CalendarSharedContentApiTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");
    private static final byte[] PNG = new byte[] {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3
    };

    @Autowired private CalendarShareService shares;
    @Autowired private CalendarShareContentGrantService grants;
    @Autowired private CalendarAttachmentBindingService attachmentBindings;
    @Autowired private CalendarKnowledgeBindingService knowledgeBindings;
    @Autowired private CalendarKnowledgeExcerptService excerpts;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private MediaStorageService media;
    @Autowired private UserKnowledgeService facts;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void recipientGetsOnlyGrantedContentThroughSafeHttpResponses() throws Exception {
        Fixture fixture = fixture();

        MvcResult attachment = mockMvc.perform(get(
                                "/api/calendar/shared-content/{grantId}/attachment",
                                fixture.attachmentGrantId()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(attachment.getResponse().getContentType()).isEqualTo("image/png");
        assertThat(attachment.getResponse().getContentAsByteArray()).containsExactly(PNG);
        assertPrivateHeaders(attachment);
        String disposition = attachment.getResponse().getHeader("Content-Disposition");
        assertThat(disposition)
                .contains("inline")
                .doesNotContain("\r", "\n", "X-Evil:", "original-secret");

        String excerptBody = mockMvc.perform(get(
                                "/api/calendar/shared-content/{grantId}/knowledge-excerpt",
                                fixture.excerptGrantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.title").value("可分享摘要"))
                .andExpect(jsonPath("$.text").value("只分享集合地點"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(excerptBody)
                .doesNotContain(
                        fixture.planId().toString(),
                        fixture.shareId().toString(),
                        "workspace",
                        "owner",
                        "binding");
    }

    @Test
    void randomWrongKindAndRevokedAccessUseTheSameSafeNotFoundContract()
            throws Exception {
        Fixture fixture = fixture();

        assertSafeNotFound(
                fixture.attachmentGrantId(), "knowledge-excerpt");
        assertSafeNotFound(fixture.excerptGrantId(), "attachment");
        assertSafeNotFound(UUID.randomUUID(), "attachment");

        inOwnerContext(fixture.ownerId(), () ->
                shares.revoke("api-share-revoke", fixture.shareId(), 1));
        assertSafeNotFound(fixture.attachmentGrantId(), "attachment");
        assertSafeNotFound(fixture.excerptGrantId(), "knowledge-excerpt");
    }

    private Fixture fixture() {
        UUID owner = UUID.randomUUID();
        seedOwner(owner);
        return inOwnerContext(owner, () -> {
            UUID planId = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    planId,
                    "安全分享",
                    CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            StoredMedia stored = media.store(
                    StoredMedia.SourceType.APP,
                    "shared-api-image",
                    "不公開媒體名稱",
                    "original-secret.png",
                    "application/octet-stream",
                    PNG);
            var attachment = attachmentBindings.bind(
                    "shared-api-binding",
                    stored.getId(),
                    CalendarAttachmentTarget.plan(planId),
                    "行程\r\nX-Evil:票",
                    0);
            UserKnowledgeFact fact = facts.remember(
                    UserKnowledgeFact.Category.PLACE_GUIDANCE,
                    "私人集合資料",
                    "完整私人內容");
            var knowledge = knowledgeBindings.bindFact(
                    "shared-api-knowledge-binding",
                    fact.getId(),
                    CalendarKnowledgeTarget.plan(planId));
            var draft = excerpts.createFactDraft(
                    "shared-api-excerpt",
                    knowledge.id(),
                    "可分享摘要",
                    "只分享集合地點");
            var approved = excerpts.approve(draft.id(), 1);
            var share = shares.createViewerShare(
                    "shared-api-plan",
                    planId,
                    LegacyAccountIds.USER_ID,
                    1);
            var attachmentGrant = grants.grantAttachment(
                    "shared-api-attachment-grant", share.id(), attachment.id());
            var excerptGrant = grants.grantKnowledgeExcerpt(
                    "shared-api-excerpt-grant", share.id(), approved.id());
            return new Fixture(
                    owner,
                    planId,
                    share.id(),
                    attachmentGrant.id(),
                    excerptGrant.id());
        });
    }

    private void seedOwner(UUID owner) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, 'Calendar API owner', 'ACTIVE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                owner);
        jdbc.update(
                "UPDATE workspace SET type = 'HOUSEHOLD' WHERE id = ?",
                LegacyAccountIds.WORKSPACE_ID);
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                LegacyAccountIds.WORKSPACE_ID,
                owner,
                LegacyAccountIds.USER_ID);
    }

    private void assertSafeNotFound(UUID grantId, String kind) throws Exception {
        String body = mockMvc.perform(get(
                                "/api/calendar/shared-content/{grantId}/{kind}",
                                grantId,
                                kind))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body)
                .doesNotContain(
                        grantId.toString(),
                        "workspace",
                        "owner",
                        "REVOKED",
                        "attachment_binding");
    }

    private static void assertPrivateHeaders(MvcResult result) {
        assertThat(result.getResponse().getHeader("Cache-Control"))
                .contains("no-store", "private");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options"))
                .isEqualTo("nosniff");
        assertThat(result.getResponse().getHeader("Cross-Origin-Resource-Policy"))
                .isEqualTo("same-origin");
        assertThat(result.getResponse().getHeader("Referrer-Policy"))
                .isEqualTo("no-referrer");
    }

    private <T> T inOwnerContext(UUID owner, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(
                        owner,
                        LegacyAccountIds.WORKSPACE_ID,
                        WorkspaceChannel.TEST))) {
            return work.get();
        }
    }

    private record Fixture(
            UUID ownerId,
            UUID planId,
            UUID shareId,
            UUID attachmentGrantId,
            UUID excerptGrantId) {}
}
