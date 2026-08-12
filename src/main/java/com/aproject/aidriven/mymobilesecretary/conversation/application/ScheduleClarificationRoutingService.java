package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationCapability;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraft;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ScheduleClarificationDraftStatus;
import com.aproject.aidriven.mymobilesecretary.conversation.persistence.ScheduleClarificationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic router for parallel typed schedule drafts; never stores utterance text. */
@Service
public class ScheduleClarificationRoutingService {
    static final String SELECTION_CODE = "schedule-draft-selection.target";

    private final ConversationScopeResolver resolver;
    private final ConversationPendingQuestionService pendingQuestions;
    private final ScheduleClarificationDraftRepository drafts;
    private final Clock clock;

    public ScheduleClarificationRoutingService(
            ConversationScopeResolver resolver,
            ConversationPendingQuestionService pendingQuestions,
            ScheduleClarificationDraftRepository drafts, Clock clock) {
        this.resolver = resolver;
        this.pendingQuestions = pendingQuestions;
        this.drafts = drafts;
        this.clock = clock;
    }

    @Transactional
    public Optional<IntentResult> answerSelection(String text) {
        Optional<ConversationPendingQuestion> pending = pendingQuestions.current();
        if (pending.isEmpty() || !SELECTION_CODE.equals(pending.orElseThrow().getQuestionCode())) {
            return Optional.empty();
        }
        List<ScheduleClarificationDraft> candidates = currentDrafts();
        if (candidates.isEmpty()) {
            pending.orElseThrow().cancel(Instant.now(clock));
            return Optional.of(IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                    "目前沒有可繼續的草稿，也沒有建立或修改行程。"));
        }
        if (candidates.size() == 1) {
            return Optional.of(resumeResult(candidates.getFirst()));
        }
        ScheduleClarificationCapability selected = selectedCapability(text, candidates);
        if (selected == null) {
            return isPotentialContinuation(text)
                    ? Optional.of(selectionResult(candidates)) : Optional.empty();
        }
        return candidates.stream().filter(draft -> draft.getCapability() == selected).findFirst()
                .map(this::resumeResult)
                .or(() -> Optional.of(selectionResult(candidates)));
    }

    @Transactional
    public Optional<IntentResult> cancelSelected(String text) {
        if (!isCancel(text)) return Optional.empty();
        List<ScheduleClarificationDraft> candidates = currentDrafts();
        UUID quoted = TrustedConversationReferenceContext.currentDraftId();
        Optional<ConversationPendingQuestion> pending = pendingQuestions.current();
        if (pending.isPresent()
                && SELECTION_CODE.equals(pending.orElseThrow().getQuestionCode())) {
            pending.orElseThrow().cancel(Instant.now(clock));
            return Optional.of(IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                    "已停止這次草稿選擇；原有草稿都保留，沒有建立或修改行程。"));
        }
        UUID target = quoted != null ? quoted : pending.map(ConversationPendingQuestion::getWorkflowId)
                .orElse(null);
        ScheduleClarificationDraft draft = candidates.stream()
                .filter(candidate -> candidate.getId().equals(target)).findFirst().orElse(null);
        if (draft == null) return Optional.empty();
        Instant now = Instant.now(clock);
        draft.cancel(now);
        if (pending.isPresent() && pending.orElseThrow().getWorkflowId().equals(draft.getId())) {
            pending.orElseThrow().cancel(now);
        }
        return Optional.of(IntentResult.message(IntentResult.Action.CONTEXT_UPDATED,
                "已取消這個尚未完成的草稿，沒有建立或修改行程。"));
    }

    @Transactional
    public Optional<IntentResult> askSelectionIfAmbiguous(String text) {
        if (pendingQuestions.current().isPresent() || !isPotentialContinuation(text)) {
            return Optional.empty();
        }
        List<ScheduleClarificationDraft> candidates = currentDrafts();
        return candidates.size() > 1 ? Optional.of(selectionResult(candidates)) : Optional.empty();
    }

    private List<ScheduleClarificationDraft> currentDrafts() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        ConversationScopeKey scope = resolver.current(context);
        Instant now = Instant.now(clock);
        return drafts
                .findAllByWorkspaceIdAndCreatedByUserIdAndChannelAndConversationScopeDigestAndStatusOrderByCreatedAtAsc(
                        context.workspaceId(), context.actorId(), context.channel(), scope.digest(),
                        ScheduleClarificationDraftStatus.PENDING)
                .stream().filter(draft -> !draft.expireIfDue(now)).toList();
    }

    private IntentResult selectionResult(List<ScheduleClarificationDraft> candidates) {
        String choices = candidates.stream().map(draft -> label(draft.getCapability()))
                .distinct().reduce((left, right) -> left + "、" + right).orElse("要繼續的草稿");
        return IntentResult.clarificationNeeded(ClarificationStep.blocking(
                SELECTION_CODE, "workflow", "要接著處理哪一個草稿：" + choices + "？", 1));
    }

    private IntentResult resumeResult(ScheduleClarificationDraft draft) {
        return IntentResult.clarificationNeeded(
                "好，現在回到「" + label(draft.getCapability()) + "」草稿。",
                nextStep(draft));
    }

    private static ClarificationStep nextStep(ScheduleClarificationDraft draft) {
        return switch (draft.getCapability()) {
            case CONDITIONAL_RECURRENCE -> recurrenceStep(draft);
            case CONDITIONAL_VENUE -> venueStep(draft);
            case MONTHLY_ORDINAL -> monthlyStep(draft);
        };
    }

    private static ClarificationStep recurrenceStep(ScheduleClarificationDraft draft) {
        if (!draft.isRecurrenceExplicit()) return step("conditional-recurrence.recurrence", "recurrence", "這個安排是否要每週固定？");
        if (draft.getWeekday() == null) return step("conditional-recurrence.weekday", "weekday", "每週星期幾進行？");
        if (draft.getStartTime() == null) return step("conditional-recurrence.start-time", "startTime", "幾點開始？");
        if (!draft.isTimePeriodExplicit()) return step("conditional-recurrence.time-period", "timePeriod", "這個鐘點是上午還是晚上？");
        if (draft.getDurationMinutes() == null) return step("conditional-recurrence.duration", "duration", "每次持續多久？");
        if (draft.getTitle() == null) return step("conditional-recurrence.title", "title", "這個行程要叫什麼名稱？");
        if (draft.getClosurePolicy() != null && !"NONE".equals(draft.getClosurePolicy())
                && draft.getJurisdiction() == null) return step("conditional-recurrence.jurisdiction", "jurisdiction", "停班停課要以哪個縣市為準？");
        return step("conditional-recurrence.confirm", "confirmation", "要繼續完成這個條件式週期草稿嗎？");
    }

    private static ClarificationStep venueStep(ScheduleClarificationDraft draft) {
        if (draft.getEventAt() == null) return step("conditional-venue.event-at", "eventAt", "活動是哪一天幾點開始？");
        if (draft.getDurationMinutes() == null) return step("conditional-venue.duration", "duration", "活動持續多久？");
        if (draft.getDecisionAt() == null) return step("conditional-venue.decision-at", "decisionAt", "要在什麼時候提醒你決定場地？");
        if (!draft.isDecisionPeriodExplicit()) return step("conditional-venue.decision-period", "decisionPeriod", "決定場地的鐘點是上午還是下午？");
        if (draft.getPrimaryPlace() == null || draft.getFallbackPlace() == null) return step("conditional-venue.places", "places", "原定場地和備用場地分別是哪裡？");
        if (draft.getTitle() == null) return step("conditional-venue.title", "activityTitle", "這個活動要叫什麼名稱？");
        return step("conditional-venue.confirm", "confirmation", "要繼續完成這個條件場地草稿嗎？");
    }

    private static ClarificationStep monthlyStep(ScheduleClarificationDraft draft) {
        if (draft.getOrdinalValue() == null || draft.getWeekday() == null) return step("monthly-ordinal.rule", "ordinalWeekday", "要排每月第幾個星期幾？");
        if (draft.getStartTime() == null) return step("monthly-ordinal.start-time", "startTime", "幾點開始？");
        if (!draft.isTimePeriodExplicit()) return step("monthly-ordinal.time-period", "timePeriod", "這個鐘點是上午還是晚上？");
        if (draft.getDurationMinutes() == null) return step("monthly-ordinal.duration", "duration", "每次持續多久？");
        if (draft.getTitle() == null) return step("monthly-ordinal.title", "title", "這個行程要叫什麼名稱？");
        return step("monthly-ordinal.confirm", "confirmation", "要繼續完成這個每月週次草稿嗎？");
    }

    private static ClarificationStep step(String code, String slot, String prompt) {
        return ClarificationStep.blocking(code, slot, prompt, 1);
    }

    private static ScheduleClarificationCapability selectedCapability(
            String text, List<ScheduleClarificationDraft> candidates) {
        String value = normalize(text);
        for (int index = 0; index < candidates.size(); index++) {
            ScheduleClarificationDraft candidate = candidates.get(index);
            ScheduleClarificationCapability capability = candidate.getCapability();
            if (value.equals(switch (index) {
                case 0 -> "第一個";
                case 1 -> "第二個";
                case 2 -> "第三個";
                default -> "";
            })) return capability;
            if (switch (capability) {
                case CONDITIONAL_RECURRENCE -> value.contains("條件式週期") || value.equals("週期");
                case CONDITIONAL_VENUE -> value.contains("條件場地") || value.equals("場地");
                case MONTHLY_ORDINAL -> value.contains("每月週次") || value.contains("每月第");
            }) return capability;
        }
        return null;
    }

    private static String label(ScheduleClarificationCapability capability) {
        return switch (capability) {
            case CONDITIONAL_RECURRENCE -> "條件式週期";
            case CONDITIONAL_VENUE -> "條件場地";
            case MONTHLY_ORDINAL -> "每月週次";
        };
    }

    private static boolean isPotentialContinuation(String text) {
        String value = normalize(text);
        return !value.isBlank() && value.length() <= 60 && !value.endsWith("？")
                && !value.endsWith("?") && !isCancel(value) && !startsNewRequest(value);
    }

    private static boolean startsNewRequest(String value) {
        return value.contains("新增") || value.contains("建立") || value.contains("記錄")
                || value.contains("安排") || value.contains("提醒我") || value.contains("幫我")
                || value.startsWith("我要") || value.startsWith("我想")
                || value.contains("修改") || value.contains("刪除") || value.contains("取消")
                || value.contains("設定") || value.contains("查詢") || value.contains("列出")
                || value.contains("完成") || value.contains("購買")
                || value.contains("沒聽懂") || value.contains("答錯") || value.contains("回覆有問題");
    }

    private static boolean isCancel(String text) {
        String value = normalize(text);
        return value.contains("取消") && (value.contains("草稿") || value.contains("這個"));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
