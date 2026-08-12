package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compositional evidence for a transport-planning request with an explicit origin and destination. */
final class RouteItineraryTurnRouter {

    private static final String ACTION = "(?:出發|啟程|起程|動身|開始走|走過去|走)?";
    private static final Pattern FROM_ORIGIN_DESTINATION = Pattern.compile(
            ".*?(?:從|由|自)(?<origin>[^,，。；;]{2,2048}?)"
                    + ACTION
                    + "(?:到|前往)(?<destination>[^,，。；;]{2,80}?)(?:,|，|。|；|;|$).*?");
    private static final Pattern AS_START_ORIGIN_DESTINATION = Pattern.compile(
            ".*?以(?<origin>[^,，。；;]{2,2048}?)(?:為|作為|當作)起點"
                    + "(?:到|前往)(?<destination>[^,，。；;]{2,80}?)(?:,|，|。|；|;|$).*?");

    enum Kind { PLAN_TRANSPORT_ITINERARY }

    record Decision(Kind kind, String origin, String destination) {}

    private RouteItineraryTurnRouter() {}

    static Optional<Decision> route(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).strip();
        if (!containsAny(
                normalized, "規劃", "規畫", "安排", "排進", "幫我排", "建立行程")) {
            return Optional.empty();
        }
        Matcher matcher = FROM_ORIGIN_DESTINATION.matcher(normalized);
        if (!matcher.matches()) {
            matcher = AS_START_ORIGIN_DESTINATION.matcher(normalized);
            if (!matcher.matches()) return Optional.empty();
        }
        String origin = clean(matcher.group("origin"));
        String destination = clean(matcher.group("destination"));
        if (origin.isBlank() || destination.isBlank()) return Optional.empty();
        return Optional.of(new Decision(Kind.PLAN_TRANSPORT_ITINERARY, origin, destination));
    }

    private static String clean(String value) {
        return value.replaceAll("(?:明天|今天|後天|早上|上午|中午|下午|晚上|\\d{1,2}點)+", "")
                .replaceAll("(?:的話|的行程|行程)$", "")
                .strip();
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }
}
