package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarShareScopeTest {

    @Test
    void wholePlanHasNoExplicitTargets() {
        CalendarShareScope scope = CalendarShareScope.liveWholePlan();

        assertThat(scope.mode()).isEqualTo(CalendarShareScopeMode.LIVE_WHOLE_PLAN);
        assertThat(scope.targetIds()).isEmpty();
    }

    @Test
    void selectedTargetsAreCanonicalAndDeduplicated() {
        UUID later = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID earlier = UUID.fromString("00000000-0000-0000-0000-000000000001");

        CalendarShareScope scope =
                CalendarShareScope.selectedNodes(List.of(later, earlier, later));

        assertThat(scope.mode()).isEqualTo(CalendarShareScopeMode.SELECTED_NODES);
        assertThat(scope.targetIds()).containsExactly(earlier, later);
        assertThat(scope.semanticKey())
                .isEqualTo(
                        "SELECTED_NODES|00000000-0000-0000-0000-000000000001,"
                                + "ffffffff-ffff-ffff-ffff-ffffffffffff");
    }

    @Test
    void selectedScopeRequiresAtLeastOneTarget() {
        assertThatThrownBy(() -> CalendarShareScope.selectedActivities(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CalendarShareScope.selectedNodes(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wholePlanRejectsExplicitTargets() {
        assertThatThrownBy(() -> new CalendarShareScope(
                        CalendarShareScopeMode.LIVE_WHOLE_PLAN,
                        List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
