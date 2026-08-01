package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Selects exactly one understanding provider before any model call.
 *
 * <p>The specialized route is read-only and provider-neutral. Once it is selected, failures are
 * propagated to {@link IntentService}'s safe fallback instead of calling a second model.</p>
 */
@Primary
@Component
@ConditionalOnProperty(prefix = "app.intent", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public final class ProviderNeutralIntentInterpreter implements IntentInterpreter {

    private final AnthropicIntentInterpreter legacy;
    private final ScheduleAnalysisUnderstandingClient scheduleAnalysisClient;
    private final ScheduleAnalysisRoutePolicy scheduleAnalysisRoutePolicy;

    ProviderNeutralIntentInterpreter(
            AnthropicIntentInterpreter legacy,
            ScheduleAnalysisUnderstandingClient scheduleAnalysisClient,
            ScheduleAnalysisRoutePolicy scheduleAnalysisRoutePolicy) {
        this.legacy = legacy;
        this.scheduleAnalysisClient = scheduleAnalysisClient;
        this.scheduleAnalysisRoutePolicy = scheduleAnalysisRoutePolicy;
    }

    @Override
    public IntentScript interpret(String text, Instant now) {
        return interpret(text, now, ConversationSnapshot.empty());
    }

    @Override
    public IntentScript interpret(String text, Instant now, ConversationSnapshot context) {
        if (!scheduleAnalysisRoutePolicy.matches(text) || !scheduleAnalysisClient.available()) {
            return legacy.interpret(text, now, context);
        }
        ScheduleAnalysisUnderstandingClient.Decision decision =
                Objects.requireNonNull(scheduleAnalysisClient.understand(text),
                        "schedule analysis decision");
        return toIntentScript(text, decision);
    }

    private static IntentScript toIntentScript(
            String text, ScheduleAnalysisUnderstandingClient.Decision decision) {
        EnumSet<ScheduleAnalysisUnderstandingClient.Facet> facets = decision.facets().isEmpty()
                ? EnumSet.noneOf(ScheduleAnalysisUnderstandingClient.Facet.class)
                : EnumSet.copyOf(decision.facets());
        if (facets.contains(ScheduleAnalysisUnderstandingClient.Facet.ADJACENT_GAPS)
                && !facets.contains(ScheduleAnalysisUnderstandingClient.Facet.LONGEST_ITEM)) {
            throw new IllegalStateException("ADJACENT_GAPS requires LONGEST_ITEM");
        }
        if (facets.isEmpty() && !decision.containsOtherRequest()) {
            throw new IllegalStateException("specialized route returned no schedule-analysis facet");
        }

        List<IntentCommand> commands = new ArrayList<>();
        if (facets.contains(ScheduleAnalysisUnderstandingClient.Facet.BUSIEST_DAY)) {
            commands.add(query(IntentCommand.Type.ASK_BUSY_SCHEDULE_DAY, text));
        }
        if (facets.contains(ScheduleAnalysisUnderstandingClient.Facet.LONGEST_ITEM)) {
            commands.add(query(IntentCommand.Type.ASK_LONGEST_SCHEDULE, text));
        }
        if (facets.contains(ScheduleAnalysisUnderstandingClient.Facet.ADJACENT_GAPS)
                || decision.containsOtherRequest()) {
            commands.add(unknown(text,
                    facets.contains(ScheduleAnalysisUnderstandingClient.Facet.ADJACENT_GAPS),
                    decision.containsOtherRequest()));
        }
        return new IntentScript(List.copyOf(commands));
    }

    private static IntentCommand query(IntentCommand.Type type, String sourceText) {
        return new IntentCommand(type, null, null, null, null, null, null, null,
                null, null, null, null, null, null, sourceText);
    }

    private static IntentCommand unknown(
            String sourceText, boolean adjacentGap, boolean containsOtherRequest) {
        List<String> limitations = new ArrayList<>();
        if (adjacentGap) {
            limitations.add("指定行程前後的相鄰空檔查詢目前還不能完整處理");
        }
        if (containsOtherRequest) {
            limitations.add("同一句還有行程分析以外的其他要求，需要分開確認");
        }
        String reason = String.join("；", limitations)
                + "。這次只會回答已可靠辨識的唯讀查詢，不會建立或修改資料。";
        return new IntentCommand(IntentCommand.Type.UNKNOWN, null, null, null, null,
                null, null, reason, null, null, null, null, null, null, sourceText);
    }
}
