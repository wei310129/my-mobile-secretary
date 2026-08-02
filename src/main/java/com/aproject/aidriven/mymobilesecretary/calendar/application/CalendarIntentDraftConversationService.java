package com.aproject.aidriven.mymobilesecretary.calendar.application;

import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.DraftView;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarIntentDraftService.Status;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusBinding;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusDirective;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Secretary-style bridge from an actor-private typed draft to conversation focus. */
@Service
public class CalendarIntentDraftConversationService {

    public static final String FOCUS_DOMAIN = "CALENDAR_DRAFT";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final CalendarIntentDraftService drafts;
    private final ConversationFocusService focuses;
    private final CalendarIntentDraftPreflightResponsePolicy preflightResponses;

    public CalendarIntentDraftConversationService(
            CalendarIntentDraftService drafts,
            ConversationFocusService focuses,
            CalendarIntentDraftPreflightResponsePolicy preflightResponses) {
        this.drafts = drafts;
        this.focuses = focuses;
        this.preflightResponses = preflightResponses;
    }

    public IntentResult propose(IntentCommand command) {
        if (!CalendarIntentRecurrencePolicy.resolve(command).valid()) {
            return recurrenceClarification();
        }
        DraftView draft = drafts.propose(command);
        return IntentResult.message(
                        IntentResult.Action.SUGGESTION_MADE,
                        "我先整理成「%s」，時間是 %s，尚未放進行事曆。要照這個版本建立嗎？"
                                .formatted(draft.title(), placement(draft.placement())))
                .withFocusBinding(binding(draft));
    }

    public IntentResult createWithPreflight(IntentCommand command) {
        if (!CalendarIntentRecurrencePolicy.resolve(command).valid()) {
            return recurrenceClarification();
        }
        DraftView draft = drafts.propose(command);
        var confirmation = drafts.confirm(draft.id(), draft.revision());
        if (confirmation.awaitingRouteConfirmation()) {
            return IntentResult.message(
                            IntentResult.Action.SUGGESTION_MADE,
                            preflightResponses.describe(confirmation.preflight()))
                    .withFocusBinding(binding(confirmation.draft()));
        }
        DraftView saved = confirmation.draft();
        return IntentResult.message(
                IntentResult.Action.SCHEDULE_CONFIRMED,
                "已建立行程「%s」，時間是 %s。"
                        .formatted(saved.title(), placement(saved.placement())));
    }

    public Optional<IntentResult> reviseActive(IntentCommand command) {
        Optional<FocusedDraft> focused = activeDraft();
        if (focused.isEmpty() || !referencesActive(command, focused.orElseThrow())) {
            return Optional.empty();
        }
        DraftView changed = drafts.revise(
                focused.orElseThrow().id(),
                focused.orElseThrow().revision(),
                correction(command));
        return Optional.of(IntentResult.message(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "好，我把「%s」的提案改成 %s，還沒有放進行事曆。要照新版建立嗎？"
                                .formatted(changed.title(), placement(changed.placement())))
                .withFocusBinding(binding(changed)));
    }

    public Optional<IntentResult> confirmActive() {
        Optional<FocusedDraft> focused = activeDraft();
        if (focused.isEmpty()) return Optional.empty();
        var confirmation = drafts.confirm(
                focused.orElseThrow().id(), focused.orElseThrow().revision());
        if (confirmation.awaitingRouteConfirmation()) {
            return Optional.of(IntentResult.message(
                            IntentResult.Action.SUGGESTION_MADE,
                            preflightResponses.describe(confirmation.preflight()))
                    .withFocusBinding(binding(confirmation.draft())));
        }
        DraftView saved = confirmation.draft();
        return Optional.of(IntentResult.message(
                        IntentResult.Action.SCHEDULE_CONFIRMED,
                        "好，已把「%s」放進行事曆。".formatted(saved.title()))
                .withFocusDirective(binding(saved), ConversationFocusDirective.INVALIDATE_TARGET));
    }

    public Optional<IntentResult> discardActive() {
        Optional<FocusedDraft> focused = activeDraft();
        if (focused.isEmpty()) return Optional.empty();
        DraftView discarded = drafts.discard(
                focused.orElseThrow().id(), focused.orElseThrow().revision());
        return Optional.of(IntentResult.message(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "好，已放棄「%s」的提案，行事曆沒有新增資料。"
                                .formatted(discarded.title()))
                .withFocusDirective(
                        binding(discarded), ConversationFocusDirective.INVALIDATE_TARGET));
    }

    private Optional<FocusedDraft> activeDraft() {
        return focuses.activeFocus()
                .filter(focus -> FOCUS_DOMAIN.equals(focus.getRootDomain()))
                .filter(focus -> focus.getWorkflowId() != null)
                .map(focus -> new FocusedDraft(
                        focus.getWorkflowId(), revision(focus), focus.getSafeLabel()));
    }

    private static long revision(ConversationFocus focus) {
        String activity = focus.getActivityCode();
        if (activity == null || !activity.startsWith("revision:")) {
            throw new IllegalStateException("Calendar proposal focus has no trusted revision");
        }
        try {
            long value = Long.parseLong(activity.substring("revision:".length()));
            if (value < 1) throw new NumberFormatException("non-positive revision");
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Calendar proposal focus revision is invalid", exception);
        }
    }

    private static boolean referencesActive(IntentCommand command, FocusedDraft focused) {
        String reference = command.safeOptions().referenceTitle();
        if (reference == null || reference.isBlank()) reference = command.title();
        return reference == null
                || reference.isBlank()
                || normalize(reference).equals(normalize(focused.label()));
    }

    private static String normalize(String value) {
        return value.replaceAll("[\\s　]+", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static IntentCommand correction(IntentCommand command) {
        return new IntentCommand(
                command.type(),
                command.safeOptions().newTitle(),
                null,
                command.startAt(),
                command.endAt(),
                command.placeName(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                command.options(),
                command.sourceText());
    }

    private static ConversationFocusBinding binding(DraftView draft) {
        return ConversationFocusBinding.workflowActivity(
                FOCUS_DOMAIN,
                draft.id(),
                draft.title(),
                "revision:" + draft.revision(),
                draft.status() == Status.PENDING ? "等待確認" : "已結束");
    }

    private static IntentResult recurrenceClarification() {
        return IntentResult.clarificationNeeded(
                "請明確告訴我週期是每天、每個平日、每週，或每月第幾個星期幾；"
                        + "確認前不會建立行程。");
    }

    private static String placement(CalendarPlacement placement) {
        if (placement instanceof CalendarPlacement.TimedInterval interval) {
            ZoneId zone = interval.zoneId();
            return "%s 到 %s".formatted(
                    ZonedDateTime.ofInstant(interval.start(), zone).format(DATE_TIME),
                    ZonedDateTime.ofInstant(interval.end(), zone).format(DATE_TIME));
        }
        if (placement instanceof CalendarPlacement.TimedPoint point) {
            return ZonedDateTime.ofInstant(point.time(), point.zoneId()).format(DATE_TIME);
        }
        CalendarPlacement.AllDay allDay = (CalendarPlacement.AllDay) placement;
        return allDay.endExclusive().equals(allDay.start().plusDays(1))
                ? allDay.start().toString()
                : "%s 到 %s".formatted(allDay.start(), allDay.endExclusive().minusDays(1));
    }

    private record FocusedDraft(java.util.UUID id, long revision, String label) {}
}
