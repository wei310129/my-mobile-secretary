package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.Entry;
import com.aproject.aidriven.mymobilesecretary.travel.application.TravelItineraryDraftService.Payload;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelItineraryDraft.Status;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TravelItineraryDraftAnswerServiceTest {

    private TravelItineraryDraftService drafts;
    private TravelItineraryDraftAnswerService service;

    @BeforeEach
    void setUp() {
        drafts = mock(TravelItineraryDraftService.class);
        service = new TravelItineraryDraftAnswerService(drafts);
    }

    @Test
    void previewSeparatesScheduleActivitiesAndNotices() {
        DraftView draft = view(Status.PENDING);

        String message = service.previewMessage(draft);

        assertThat(message).contains(
                "日本郵輪行程", "11-18 08:00–09:00", "靠港", "那霸港",
                "岸上觀光抽獎", "護照須隨身攜帶", "確認匯入行程表",
                "確認前不會建立正式行程");
    }

    @Test
    void explicitItineraryConfirmationCrossesMutationBoundary() {
        when(drafts.confirmLatest()).thenReturn(java.util.Optional.of(view(Status.CONFIRMED)));
        AtomicBoolean boundary = new AtomicBoolean();

        IntentResult result = service.answer("確認匯入行程表",
                () -> boundary.set(true)).orElseThrow();

        assertThat(boundary).isTrue();
        assertThat(result.action()).isEqualTo(IntentResult.Action.TRAVEL_ITINERARY_CONFIRMED);
        assertThat(result.message()).contains("1 段", "1 項", "確認資料");
        verify(drafts).confirmLatest();
    }

    @Test
    void genericConfirmationIsNotConsumed() {
        assertThat(service.answer("確認", () -> {
        })).isEmpty();
    }

    private static DraftView view(Status status) {
        return new DraftView(7L, "日本郵輪行程", status,
                new Payload(List.of(new Entry(
                        "11-18", "08:00", "09:00", "靠港", "那霸港", "集合後下船")),
                        List.of("岸上觀光抽獎"), List.of("護照須隨身攜帶")),
                Instant.parse("2026-08-17T00:00:00Z"));
    }
}
