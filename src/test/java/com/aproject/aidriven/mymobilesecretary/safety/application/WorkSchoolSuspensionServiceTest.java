package com.aproject.aidriven.mymobilesecretary.safety.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.notification.NotificationPublisher;
import com.aproject.aidriven.mymobilesecretary.safety.domain.WorkSchoolSuspensionDraft;
import com.aproject.aidriven.mymobilesecretary.safety.persistence.WorkSchoolSuspensionDraftRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class WorkSchoolSuspensionServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private WorkSchoolSuspensionDraftRepository repository;
    private SuspensionOfficialClient officialClient;
    private NotificationPublisher notificationPublisher;
    private WorkSchoolSuspensionService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkSchoolSuspensionDraftRepository.class);
        officialClient = mock(SuspensionOfficialClient.class);
        notificationPublisher = mock(NotificationPublisher.class);
        service = new WorkSchoolSuspensionService(repository, officialClient,
                notificationPublisher, mock(ApplicationEventPublisher.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearScope() {
        WorkspaceContextHolder.clear();
    }

    @Test
    void imageIsStoredAsUnverifiedAndAsksForOfficialCheck() {
        var result = service.ingestImage(LocalDate.of(2026, 7, 9), List.of(
                new WorkSchoolSuspensionService.RegionStatus(
                        "臺北市", "停止上班、停止上課")));

        assertThat(result.message()).contains("圖片抄錄", "尚未視為官方事實",
                "回覆「不用」", "10 分鐘沒有回覆也會自動查核");
        verify(repository).save(any(WorkSchoolSuspensionDraft.class));
    }

    @Test
    void explicitNoDeclinesWithoutCallingOfficialSite() throws Exception {
        WorkSchoolSuspensionDraft draft = draft();
        when(repository.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                ACTOR, WorkSchoolSuspensionDraft.Status.PENDING_CONFIRMATION))
                .thenReturn(Optional.of(draft));
        AtomicBoolean mutation = new AtomicBoolean();

        var result = inScope(() -> service.answer("不用再查了", () -> mutation.set(true)))
                .orElseThrow();

        assertThat(mutation).isTrue();
        assertThat(draft.getStatus()).isEqualTo(WorkSchoolSuspensionDraft.Status.DECLINED);
        assertThat(result.message()).contains("不會再連線官方網站");
    }

    @Test
    void explicitYesChecksOfficialSourceAndKeepsSourceInReply() throws Exception {
        WorkSchoolSuspensionDraft draft = draft();
        when(repository.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                ACTOR, WorkSchoolSuspensionDraft.Status.PENDING_CONFIRMATION))
                .thenReturn(Optional.of(draft));
        when(officialClient.verify(any(), any())).thenReturn(
                new SuspensionOfficialClient.Verification(true, false,
                        "官方目前頁面未能證實", "https://www.dgpa.gov.tw/typh/daily/nds.html"));

        var result = inScope(() -> service.answer("好，請查", () -> { })).orElseThrow();

        assertThat(draft.getStatus())
                .isEqualTo(WorkSchoolSuspensionDraft.Status.OFFICIAL_NOT_CONFIRMED);
        assertThat(result.message()).contains("官方目前頁面未能證實", "https://www.dgpa.gov.tw");
    }

    @Test
    void unrelatedSentenceContainingWantDoesNotAccidentallyStartVerification() throws Exception {
        WorkSchoolSuspensionDraft draft = draft();
        when(repository.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                ACTOR, WorkSchoolSuspensionDraft.Status.PENDING_CONFIRMATION))
                .thenReturn(Optional.of(draft));

        var result = inScope(() -> service.answer("我要買牛奶", () -> { }));

        assertThat(result).isEmpty();
        assertThat(draft.getStatus())
                .isEqualTo(WorkSchoolSuspensionDraft.Status.PENDING_CONFIRMATION);
    }

    @Test
    void dueUnansweredDraftIsVerifiedAndNotificationIsQueued() {
        WorkSchoolSuspensionDraft draft = draft();
        when(repository.findByStatusAndVerifyAfterLessThanEqualOrderByVerifyAfterAsc(
                WorkSchoolSuspensionDraft.Status.PENDING_CONFIRMATION, NOW))
                .thenReturn(List.of(draft));
        when(officialClient.verify(any(), any())).thenReturn(
                new SuspensionOfficialClient.Verification(false, false,
                        "官方頁面目前無法連線", "https://www.dgpa.gov.tw/typh/daily/nds.html"));

        assertThat(service.verifyDue()).isEqualTo(1);

        assertThat(draft.getStatus())
                .isEqualTo(WorkSchoolSuspensionDraft.Status.VERIFICATION_FAILED);
        verify(notificationPublisher).enqueue(any());
    }

    private static WorkSchoolSuspensionDraft draft() {
        return WorkSchoolSuspensionDraft.pending(LocalDate.of(2026, 7, 9),
                "臺北市：停止上班、停止上課", NOW, NOW.minusSeconds(600));
    }

    private static <T> T inScope(java.util.concurrent.Callable<T> action) throws Exception {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.LINE))) {
            return action.call();
        }
    }
}
