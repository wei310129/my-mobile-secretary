package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntentValidationDiagnosticTest {

    @Test
    void mutationBoundaryDefaultsNewCommandsToFailClosed() {
        assertThat(IntentService.isPotentiallyMutating(IntentCommand.Type.ASK_LAST_ACTIVITY)).isFalse();
        assertThat(IntentService.isPotentiallyMutating(IntentCommand.Type.PLAN_TRIP)).isFalse();
        assertThat(IntentService.isPotentiallyMutating(IntentCommand.Type.PLAN_PACKING_LIST)).isFalse();
        assertThat(IntentService.isPotentiallyMutating(
                IntentCommand.Type.LIST_PACKING_PREFERENCES)).isFalse();
        assertThat(IntentService.isPotentiallyMutating(
                IntentCommand.Type.SET_PACKING_PREFERENCE)).isTrue();
        assertThat(IntentService.isPotentiallyMutating(
                IntentCommand.Type.SHOW_TRAVEL_ITINERARY_DRAFT)).isFalse();
        assertThat(IntentService.isPotentiallyMutating(
                IntentCommand.Type.CONFIRM_TRAVEL_ITINERARY_DRAFT)).isTrue();
        assertThat(IntentService.isPotentiallyMutating(
                IntentCommand.Type.DISCARD_TRAVEL_ITINERARY_DRAFT)).isTrue();
        assertThat(IntentService.isPotentiallyMutating(IntentCommand.Type.CREATE_TASK)).isTrue();
        assertThat(IntentService.isPotentiallyMutating(IntentCommand.Type.CANCEL_TASK)).isTrue();
        assertThat(IntentService.isPotentiallyMutating(null)).isTrue();
    }

    @Test
    void explainsKnownJavaValidationFailuresInPlainLanguage() {
        assertThat(IntentValidationDiagnostic.explain(
                new IllegalArgumentException("schedule missing startAt/endAt")))
                .isEqualTo("建立行程必須同時有 startAt 與 endAt");
        assertThat(IntentValidationDiagnostic.explain(
                new IllegalArgumentException("bad time: 2026/07/16 22:00")))
                .contains("ISO-8601", "2026/07/16 22:00");
        assertThat(IntentValidationDiagnostic.explain(
                new IllegalArgumentException("missing title")))
                .isEqualTo("AI 沒有提供任務或行程標題 title");
    }

    @Test
    void summarizesTheFieldsJavaActuallyReceived() {
        IntentCommand command = new IntentCommand(IntentCommand.Type.CREATE_SCHEDULE,
                "倒垃圾", null, "2026-07-16T22:00:00+08:00", null,
                null, "NORMAL", null, null, null, null, null, false);

        assertThat(IntentValidationDiagnostic.summarize(command))
                .isEqualTo("type=CREATE_SCHEDULE; title=倒垃圾; dueAt=(空); "
                        + "startAt=2026-07-16T22:00:00+08:00; endAt=(空); placeName=(空)");
    }

    @Test
    void exposesStableValidationCodesWithoutIncludingRejectedValues() {
        assertThat(IntentValidationDiagnostic.code(
                new IllegalArgumentException("schedule missing startAt")))
                .isEqualTo("MISSING_SCHEDULE_START_AT");
        assertThat(IntentValidationDiagnostic.code(
                new IllegalArgumentException("bad time: private malformed value")))
                .isEqualTo("INVALID_TIME_FORMAT")
                .doesNotContain("private malformed value");
    }
}
