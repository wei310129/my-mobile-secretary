package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.travel.application.TravelPackingPreferenceService;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelPackingPreference;
import com.aproject.aidriven.mymobilesecretary.travel.domain.TravelPackingPreference.Preference;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TravelPackingAnswerServiceTest {

    private TravelPackingPreferenceService preferences;
    private ConversationContextService context;
    private TravelPackingAnswerService service;

    @BeforeEach
    void setUp() {
        preferences = mock(TravelPackingPreferenceService.class);
        context = mock(ConversationContextService.class);
        when(preferences.list()).thenReturn(List.of());
        when(context.snapshot()).thenReturn(new ConversationSnapshot(
                null, null, null, List.of(), List.of(), "TRAVEL_INFO",
                "11月17-22日搭郵輪去日本玩", "請補齊旅行資訊"));
        service = new TravelPackingAnswerService(preferences, context);
    }

    @Test
    void packingDraftUsesRecentTripAndDoesNotInventSwimmingActivity() {
        IntentResult result = service.answer("幫我草擬一份行李清單", () -> {
            throw new AssertionError("read-only draft crossed mutation boundary");
        }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PACKING_LIST_INFO);
        assertThat(result.message()).contains("日本郵輪旅行", "護照", "登船", "暈船藥")
                .contains("沒有明確水上活動")
                .doesNotContain("泳衣、");
    }

    @Test
    void longFeatureDescriptionCanStillRequestDraftWithoutSavingEmbeddedExamples() {
        AtomicBoolean mutationStarted = new AtomicBoolean();

        IntentResult result = service.answer(
                "幫我草擬一份行李清單，並且要能夠記憶；例如使用者可能說以後不用泳衣",
                () -> mutationStarted.set(true)).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.PACKING_LIST_INFO);
        assertThat(mutationStarted).isFalse();
        verify(preferences, never()).remember(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ambiguousOmissionAsksWhetherItIsOneOffOrPermanent() {
        IntentResult result = service.answer("行李清單不要帶泳衣", () -> {
        }).orElseThrow();

        assertThat(result.action()).isEqualTo(IntentResult.Action.CLARIFICATION_NEEDED);
        assertThat(result.message()).contains("只在這次", "以後", "泳衣");
    }

    @Test
    void nonPackingDoNotCreatePhraseIsNotCapturedAsPackingPreference() {
        assertThat(service.answer(
                "明天早上九點開會一小時，先不要建，幫我看前面準備二十分鐘加後面交通四十分鐘會不會撞到",
                () -> {
                    throw new AssertionError("non-packing query crossed mutation boundary");
                })).isEmpty();
    }

    @Test
    void oneOffOmissionDoesNotChangeLongTermMemory() {
        AtomicBoolean mutationStarted = new AtomicBoolean();

        IntentResult result = service.answer("這次不要帶泳衣",
                () -> mutationStarted.set(true)).orElseThrow();

        assertThat(result.message()).contains("本次", "泳衣", "不會改動長期偏好");
        assertThat(mutationStarted).isFalse();
    }

    @Test
    void explicitPermanentPreferenceCrossesBoundaryAndKeepsReason() {
        TravelPackingPreference saved = TravelPackingPreference.create(
                "泳衣", "泳衣", Preference.NEVER_SUGGEST, "我不游泳",
                Instant.parse("2026-07-17T00:00:00Z"));
        when(preferences.remember("泳衣", Preference.NEVER_SUGGEST, "我不游泳"))
                .thenReturn(saved);
        AtomicBoolean mutationStarted = new AtomicBoolean();

        IntentResult result = service.answer(
                "以後行李清單都不要建議泳衣，因為我不游泳",
                () -> mutationStarted.set(true)).orElseThrow();

        assertThat(mutationStarted).isTrue();
        assertThat(result.action()).isEqualTo(IntentResult.Action.PACKING_PREFERENCE_UPDATED);
        assertThat(result.message()).contains("泳衣", "不再主動建議", "我不游泳");
    }
}
