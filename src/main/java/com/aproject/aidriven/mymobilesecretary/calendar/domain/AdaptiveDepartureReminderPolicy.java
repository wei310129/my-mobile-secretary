package com.aproject.aidriven.mymobilesecretary.calendar.domain;

import java.time.Duration;
import java.util.List;

/** 依單次 route duration 產生出發節點的相對提醒；本 policy 不保存永久偏好。 */
public final class AdaptiveDepartureReminderPolicy {

    private AdaptiveDepartureReminderPolicy() {
    }

    public static List<Duration> offsets(Duration routeDuration) {
        if (routeDuration == null || routeDuration.isNegative()) {
            throw new IllegalArgumentException("route duration must be non-negative");
        }
        if (routeDuration.compareTo(Duration.ofMinutes(30)) <= 0) {
            return List.of(Duration.ofMinutes(-10), Duration.ZERO);
        }
        if (routeDuration.compareTo(Duration.ofMinutes(90)) <= 0) {
            return List.of(Duration.ofMinutes(-30), Duration.ZERO);
        }
        return List.of(Duration.ofMinutes(-60), Duration.ofMinutes(-20), Duration.ZERO);
    }
}
