package com.aproject.aidriven.mymobilesecretary.venue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.geo.application.PlaceService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.UniversalLifeRecordService;
import com.aproject.aidriven.mymobilesecretary.venue.domain.VenueVisitInformation;
import com.aproject.aidriven.mymobilesecretary.venue.domain.VenueVisitInformation.Status;
import com.aproject.aidriven.mymobilesecretary.venue.persistence.VenueVisitInformationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VenueVisitInformationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();
    private VenueVisitInformationRepository repository;
    private UniversalLifeRecordService lifeRecordService;
    private VenueVisitInformationService service;

    @BeforeEach
    void setUp() {
        repository = mock(VenueVisitInformationRepository.class);
        lifeRecordService = mock(UniversalLifeRecordService.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new VenueVisitInformationService(repository, mock(PlaceService.class),
                lifeRecordService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void imageWithoutVenueStaysPendingAndAsksInsteadOfGuessing() {
        IntentResult result = inScope(() -> service.ingestImage(
                "B2 水生動物展示區", null,
                "不開放參觀；參觀須預約，10 人成團", true, 10));

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("哪個場館", "不會建立行程或預約");
        verify(repository).save(any(VenueVisitInformation.class));
    }

    @Test
    void explicitVenueFollowUpConfirmsPendingImageAndRecordsLifeEvent() {
        VenueVisitInformation pending = VenueVisitInformation.create(null,
                "B2 水生動物展示區", "不開放參觀；參觀須預約，10 人成團",
                true, 10, VenueVisitInformation.SourceType.IMAGE, NOW);
        when(repository.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                ACTOR, Status.PENDING_VENUE)).thenReturn(Optional.of(pending));
        AtomicBoolean mutation = new AtomicBoolean();

        IntentResult result = inScope(() -> service.answer(
                "這是台灣昆蟲館的參觀資訊，幫我記得，下次要去台灣昆蟲館的時候提醒我還有這個活動可以預約。",
                () -> mutation.set(true)).orElseThrow());

        assertThat(mutation).isTrue();
        assertThat(pending.getStatus()).isEqualTo(Status.ACTIVE);
        assertThat(pending.getVenueName()).isEqualTo("台灣昆蟲館");
        assertThat(result.message()).contains("B2 水生動物展示區", "10 人成團", "沒有替你建立行程");
        verify(lifeRecordService).recordDomainEvent(any(), any(), any(), any());
    }

    @Test
    void textOnlyCaptureIsSavedWithoutCreatingAnImmediateSchedule() {
        when(repository.findFirstByCreatedByUserIdAndStatusOrderByCreatedAtDesc(
                ACTOR, Status.PENDING_VENUE)).thenReturn(Optional.empty());

        IntentResult result = inScope(() -> service.answer(
                "這是台灣昆蟲館的參觀資訊，幫我記得，下次要去台灣昆蟲館時提醒我這個活動可以預約。",
                () -> { }).orElseThrow());

        assertThat(result.action()).isEqualTo(IntentResult.Action.VENUE_VISIT_INFO_SAVED);
        assertThat(result.message()).contains("台灣昆蟲館", "沒有替你建立行程或完成預約");
    }

    @Test
    void queryReturnsOnlySavedFactsWithFreshnessReminder() {
        VenueVisitInformation active = VenueVisitInformation.create("台灣昆蟲館",
                "B2 水生動物展示區", "不開放自由參觀；須預約",
                true, 10, VenueVisitInformation.SourceType.IMAGE, NOW);
        when(repository.findByCreatedByUserIdAndStatusOrderByUpdatedAtDesc(
                ACTOR, Status.ACTIVE)).thenReturn(List.of(active));

        IntentResult result = inScope(() -> service.query("台灣昆蟲館"));

        assertThat(result.message()).contains("不開放自由參觀", "至少 10 人", "最新公告");
    }

    private static <T> T inScope(java.util.concurrent.Callable<T> action) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                new WorkspaceContext(ACTOR, WORKSPACE, WorkspaceChannel.TEST))) {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }
}
