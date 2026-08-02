package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves an unqualified one-o'clock phrase without trusting a model-supplied AM/PM guess. */
final class BareClockTimePolicy {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Pattern ONE_OCLOCK = Pattern.compile(
            "(?<![零一二三四五六七八九十兩\\d])(?:1|一)點");
    private static final String[] QUALIFIERS = {
        "凌晨", "半夜", "早上", "上午", "中午", "下午", "傍晚", "晚上"
    };

    private BareClockTimePolicy() {}

    static Optional<String> clarification(String text, Clock clock) {
        if (text == null || text.isBlank()) return Optional.empty();
        String compact = text.replaceAll("\\s+", "");
        Matcher matcher = ONE_OCLOCK.matcher(compact);
        while (matcher.find()) {
            String prefix = compact.substring(Math.max(0, matcher.start() - 4), matcher.start());
            String suffix = compact.substring(matcher.end());
            if (prefix.endsWith("差")
                    || (prefix.endsWith("週") && suffix.startsWith("名"))) {
                continue;
            }
            if (java.util.Arrays.stream(QUALIFIERS).anyMatch(prefix::endsWith)) {
                continue;
            }
            LocalTime now = LocalTime.now(clock.withZone(TAIPEI));
            if (now.isBefore(LocalTime.of(21, 0))) {
                return Optional.of(
                        "你說的「1 點」我先理解為下午 1 點。要用 13:00 嗎？確認前不會建立或修改資料。");
            }
            return Optional.of(
                    "你說的「1 點」可能是凌晨或下午。這次是 01:00 還是 13:00？確認前不會建立或修改資料。");
        }
        return Optional.empty();
    }
}
