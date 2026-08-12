package com.aproject.aidriven.mymobilesecretary.planner.application;

import java.util.EnumSet;
import java.util.Set;

/** Bounded grammar 只提供 mode evidence；歧義與缺值由 typed 結果 fail closed。 */
public final class TransportModePolicy {

    private TransportModePolicy() {
    }

    public static Resolution resolve(String userText) {
        String text = userText == null ? "" : userText.strip();
        boolean motorbikeEvidence = containsAny(text, "機車", "騎機車");
        boolean bicycleEvidence = containsAny(text, "腳踏車", "自行車", "單車");
        if (motorbikeEvidence && bicycleEvidence) {
            return new Resolution(Status.AMBIGUOUS, null);
        }
        if (bicycleEvidence) {
            return new Resolution(Status.MISSING, null);
        }
        Set<RoutePlanningRequest.TravelMode> modes =
                EnumSet.noneOf(RoutePlanningRequest.TravelMode.class);
        boolean driveEvidence = containsAny(text, "開車", "駕車", "自駕");
        boolean rideHailEvidence = containsAny(text, "計程車", "小黃", "叫車", "搭車");
        boolean walkEvidence = containsAny(text, "步行", "走路");
        if (driveEvidence) {
            modes.add(RoutePlanningRequest.TravelMode.DRIVE);
        }
        if (rideHailEvidence) {
            modes.add(RoutePlanningRequest.TravelMode.RIDE_HAIL);
        }
        if (motorbikeEvidence) {
            modes.add(RoutePlanningRequest.TravelMode.TWO_WHEELER);
        }
        if (walkEvidence) {
            modes.add(RoutePlanningRequest.TravelMode.WALK);
        }
        boolean explicitTransit = containsAny(
                text,
                "大眾運輸",
                "公共運輸",
                "搭公車",
                "坐公車",
                "搭捷運",
                "坐捷運",
                "搭台鐵",
                "坐台鐵",
                "搭高鐵",
                "坐高鐵",
                "搭火車",
                "坐火車",
                "搭輕軌",
                "坐輕軌");
        boolean onlyPlaceTransitEvidence = !driveEvidence
                && !rideHailEvidence
                && !motorbikeEvidence
                && !walkEvidence
                && containsAny(text, "公車", "捷運", "台鐵", "高鐵", "火車", "輕軌");
        if (explicitTransit || onlyPlaceTransitEvidence) {
            modes.add(RoutePlanningRequest.TravelMode.TRANSIT);
        }
        if (modes.isEmpty()) {
            return new Resolution(Status.MISSING, null);
        }
        if (modes.size() > 1) {
            return new Resolution(Status.AMBIGUOUS, null);
        }
        return new Resolution(Status.RESOLVED, modes.iterator().next());
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    public enum Status {
        RESOLVED,
        MISSING,
        AMBIGUOUS
    }

    public record Resolution(Status status, RoutePlanningRequest.TravelMode mode) {
    }
}
