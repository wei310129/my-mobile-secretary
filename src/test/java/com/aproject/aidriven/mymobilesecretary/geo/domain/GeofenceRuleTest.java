package com.aproject.aidriven.mymobilesecretary.geo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * geofence 半徑驗證測試:邊界值放行、超界拒絕。
 */
class GeofenceRuleTest {

    private static final Instant NOW = Instant.parse("2026-07-09T08:00:00Z");

    /** 上下限本身是合法值(閉區間)。 */
    @ParameterizedTest
    @ValueSource(ints = {GeofenceRule.MIN_RADIUS_METERS, 100, 300, GeofenceRule.MAX_RADIUS_METERS})
    void allowsRadiusWithinBounds(int radius) {
        GeofenceRule rule = GeofenceRule.create(1L, 2L, radius, TriggerType.ENTER, NOW);

        assertThat(rule.getRadiusMeters()).isEqualTo(radius);
    }

    @ParameterizedTest
    @ValueSource(ints = {GeofenceRule.MIN_RADIUS_METERS - 1, 0, -100, GeofenceRule.MAX_RADIUS_METERS + 1})
    void rejectsRadiusOutOfBounds(int radius) {
        assertThatThrownBy(() -> GeofenceRule.create(1L, 2L, radius, TriggerType.ENTER, NOW))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RADIUS");
    }

    @Test
    void newRuleIsEnabledByDefault() {
        GeofenceRule rule = GeofenceRule.create(1L, 2L, 100, TriggerType.EXIT, NOW);

        assertThat(rule.isEnabled()).isTrue();
    }
}
