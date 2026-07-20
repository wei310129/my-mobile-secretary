package com.aproject.aidriven.mymobilesecretary.shared.time;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores actor preference and formats only human-readable message text, never API time fields. */
@Service
@Transactional
public class TimeDisplayPreferenceService {

    private static final Pattern CLOCK_TIME = Pattern.compile(
            "(?<![\\d:T+-])([01]\\d|2[0-3]):([0-5]\\d)(?!\\d)");

    private final TimeDisplayPreferenceRepository repository;
    private final Clock clock;

    public TimeDisplayPreferenceService(
            TimeDisplayPreferenceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (looksLikeMeta(compact) || !containsTimeDisplayRequest(compact)) {
            return Optional.empty();
        }
        TimeDisplayPreference.DisplayFormat format = requestedFormat(compact);
        if (format == null) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "請指定時間要顯示成 12 小時制或 24 小時制；這只改對話顯示，不改行程資料。"));
        }
        beforeMutation.run();
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        TimeDisplayPreference preference = repository
                .findByWorkspaceIdAndCreatedByUserId(scope.workspaceId(), scope.actorId())
                .orElseGet(() -> TimeDisplayPreference.create(format, Instant.now(clock)));
        preference.changeTo(format, Instant.now(clock));
        repository.save(preference);
        String label = format == TimeDisplayPreference.DisplayFormat.TWELVE_HOUR
                ? "12 小時制" : "24 小時制";
        return Optional.of(IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "時間顯示已改為%s；只影響對話文字，API／資料庫時間欄位維持 ISO 格式。"
                        .formatted(label)));
    }

    @Transactional(readOnly = true)
    public IntentResult apply(IntentResult result) {
        if (result == null || result.message() == null || result.message().isBlank()) return result;
        String formatted = applyToHumanText(result.message());
        if (formatted.equals(result.message())) return result;
        return new IntentResult(result.action(), formatted,
                result.task(), result.decision());
    }

    /** Applies the current actor's preference to user-visible text, including notifications. */
    @Transactional(readOnly = true)
    public String applyToHumanText(String text) {
        if (text == null || text.isBlank()) return text;
        WorkspaceContext scope = WorkspaceContextHolder.requireContext();
        boolean twelveHour = repository
                .findByWorkspaceIdAndCreatedByUserId(scope.workspaceId(), scope.actorId())
                .map(TimeDisplayPreference::getDisplayFormat)
                .filter(value -> value == TimeDisplayPreference.DisplayFormat.TWELVE_HOUR)
                .isPresent();
        return twelveHour ? toTwelveHour(text) : text;
    }

    static String toTwelveHour(String message) {
        Matcher matcher = CLOCK_TIME.matcher(message);
        StringBuilder formatted = new StringBuilder();
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            String period = hour < 12 ? "上午" : "下午";
            int displayHour = hour % 12 == 0 ? 12 : hour % 12;
            matcher.appendReplacement(formatted,
                    Matcher.quoteReplacement("%s %d:%s".formatted(
                            period, displayHour, matcher.group(2))));
        }
        matcher.appendTail(formatted);
        return formatted.toString();
    }

    private static boolean containsTimeDisplayRequest(String text) {
        return text.contains("時間") && (text.contains("小時制") || text.contains("時制"))
                && containsAny(text, "顯示", "格式", "輸出", "改成", "使用", "用");
    }

    private static TimeDisplayPreference.DisplayFormat requestedFormat(String text) {
        if (containsAny(text, "12小時制", "十二小時制")) {
            return TimeDisplayPreference.DisplayFormat.TWELVE_HOUR;
        }
        if (containsAny(text, "24小時制", "二十四小時制")) {
            return TimeDisplayPreference.DisplayFormat.TWENTY_FOUR_HOUR;
        }
        return null;
    }

    private static boolean looksLikeMeta(String text) {
        return containsAny(text, "測試資料", "功能開發", "需求文件", "使用者若", "使用者說");
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }
}
