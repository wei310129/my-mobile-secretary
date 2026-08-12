package com.aproject.aidriven.mymobilesecretary.conversation.application;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts a typed route origin without treating transport wording as a saved place alias. */
final class RouteOriginInputPolicy {

    private static final String ACTION = "(?:出發|啟程|起程|動身|開始走|走過去|走)";
    private static final String NEARBY = "(?:這邊|那邊|這裡|那裡)?";
    private static final String LEAD = "(?:(?:我要|我想要?|想要|請|麻煩|預計|打算|就)\\s*)?";
    private static final Pattern MAPS_LINK = Pattern.compile(
            "https?://(?:maps\\.app\\.goo\\.gl|goo\\.gl/maps|(?:www\\.)?google\\.[^/\\s]+/maps)/?[^\\s）)】》>]*",
            Pattern.CASE_INSENSITIVE);
    private static final String CURRENT =
            "(?:我(?:目前|現在|當前)?的?(?:位置|所在地)|目前(?:的)?(?:位置|所在地)|現在(?:的)?(?:位置|所在地)|當前(?:的)?位置|這個位置|這裡|這邊|此處)";
    private static final Pattern CURRENT_AFTER_ORIGIN_WORD =
            Pattern.compile("(?:從|由|自|以)\\s*" + CURRENT);
    private static final Pattern CURRENT_REFERENCE = Pattern.compile(CURRENT);
    private static final Pattern CURRENT_ONLY = Pattern.compile(
            "^" + LEAD + CURRENT + "\\s*(?:" + ACTION
                    + "|(?:作為|當作|設為|定為|為)(?:出發地點?|起點))?$");
    private static final Pattern QUOTED_ORIGIN = Pattern.compile(
            "(?:從|由|自|以)?\\s*[「『\"'](?<alias>[^」』\"'\\r\\n]{1,80})[」』\"']\\s*"
                    + "(?:" + NEARBY + ACTION + "|(?:作為|當作|設為|定為|為)(?:出發地點?|起點))$");
    private static final Pattern PREFIX_ACTION = Pattern.compile(
            "^(?:從|由|自)?\\s*(?<alias>.+?)\\s*" + NEARBY + ACTION + "$");
    private static final Pattern ROLE_SUFFIX = Pattern.compile(
            "^(?:以\\s*)?(?<alias>.+?)\\s*(?:作為|當作|設為|定為|為)\\s*(?:出發地點?|起點)$");
    private static final Pattern ROLE_PREFIX = Pattern.compile(
            "^(?:我的)?(?:出發地點?|起點)\\s*(?:是|為|[:：])\\s*(?<alias>.+)$");
    private static final Pattern FULLY_QUOTED = Pattern.compile("^[「『\"'][^」』\"'\\r\\n]{1,80}[」』\"']$");

    private RouteOriginInputPolicy() {}

    static Optional<Input> resolve(String structuredOrigin, String sourceText) {
        String structured = normalized(structuredOrigin);
        String source = normalized(sourceText);
        String link = mapsLink(structured).or(() -> mapsLink(source)).orElse(null);
        boolean current = containsCurrentReference(structured)
                || containsCurrentReference(source)
                || (link != null
                        && (containsAnyCurrentToken(structured)
                                || containsAnyCurrentToken(source)));
        if (current) {
            return Optional.of(new Input(Kind.TRANSIENT_CURRENT_LOCATION, "目前位置", link));
        }
        String alias = namedAlias(structured).orElse(null);
        return alias == null
                ? Optional.empty()
                : Optional.of(new Input(Kind.NAMED_PLACE, alias, null));
    }

    static Optional<String> namedAlias(String raw) {
        String value = normalized(raw);
        if (value == null || value.length() > 80) return Optional.empty();
        if (FULLY_QUOTED.matcher(value).matches()) {
            return bounded(value.substring(1, value.length() - 1));
        }
        String candidate = value.replaceFirst("^" + LEAD, "").strip();
        Matcher quoted = QUOTED_ORIGIN.matcher(candidate);
        if (quoted.find()) return bounded(quoted.group("alias"));
        for (Pattern pattern : new Pattern[] {ROLE_PREFIX, ROLE_SUFFIX, PREFIX_ACTION}) {
            Matcher matcher = pattern.matcher(candidate);
            if (matcher.matches()) return bounded(unquote(matcher.group("alias")));
        }
        return bounded(unquote(candidate));
    }

    static boolean isTransientAlias(String alias) {
        return "目前位置".equals(alias);
    }

    private static boolean containsCurrentReference(String value) {
        return value != null
                && (CURRENT_AFTER_ORIGIN_WORD.matcher(value).find()
                        || CURRENT_ONLY.matcher(value).matches());
    }

    private static boolean containsAnyCurrentToken(String value) {
        return value != null && CURRENT_REFERENCE.matcher(value).find();
    }

    private static Optional<String> mapsLink(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = MAPS_LINK.matcher(value);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private static Optional<String> bounded(String value) {
        if (value == null) return Optional.empty();
        String result = value.strip();
        if (result.isBlank() || result.length() > 80 || result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    private static String unquote(String value) {
        return value.strip().replaceAll("^[「『\"']+|[」』\"']+$", "").strip();
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC);
    }

    enum Kind {
        NAMED_PLACE,
        TRANSIENT_CURRENT_LOCATION
    }

    record Input(Kind kind, String alias, String query) {}
}
