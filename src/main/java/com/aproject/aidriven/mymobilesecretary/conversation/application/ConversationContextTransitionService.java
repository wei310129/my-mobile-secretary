package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import com.aproject.aidriven.mymobilesecretary.intent.application.ClarificationStep;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Guards unfinished typed workflows when an inbound turn may start another operation. */
@Service
public class ConversationContextTransitionService {

    static final String TARGET_CODE = "conversation.context-target";
    static final String NEW_CONTENT_CODE = "conversation.new-operation-content";
    private static final Pattern WEEKDAY = Pattern.compile(
            "(?:週|星期|禮拜)[一二三四五六日天]");
    private static final Pattern EXACT_TIME = Pattern.compile(
            "(?:[0-9０-９〇一兩二三四五六七八九十]+\\s*[點点時时:：])");
    private static final Set<String> CLEAR_AND_RESTART = Set.of(
            "全部清除重來", "清除重來", "全部取消重來", "取消全部重來",
            "全部清掉重來", "清掉重來", "全部清除重新開始");
    private static final Set<String> CURRENT_OPERATION_CANCEL = Set.of(
            "取消",
            "取消這次操作",
            "取消這次的操作",
            "取消這個操作",
            "取消目前操作",
            "取消這次行程建立",
            "取消這次的行程建立",
            "這次不要了",
            "這個不要了",
            "放棄這次操作",
            "放棄這次的操作");
    private static final Set<String> CURRENT_OPERATION_TREE_CANCEL = Set.of(
            "取消整個行程規劃",
            "取消整個路線規劃",
            "整個行程都取消",
            "整個路線都取消");
    private static final Set<String> COMPLETED_OPERATION_AUXILIARY_QUESTIONS = Set.of(
            "route.departure-reminder",
            "route.parking-buffer",
            "route.ride-hail-wait");

    private final ConversationPendingQuestionService pendingQuestions;
    private final ConversationFocusCapabilityCatalog capabilities;
    private final ConversationOperationLifecycleService operationLifecycle;

    @org.springframework.beans.factory.annotation.Autowired
    public ConversationContextTransitionService(
            ConversationPendingQuestionService pendingQuestions,
            ConversationFocusCapabilityCatalog capabilities,
            ConversationOperationLifecycleService operationLifecycle) {
        this.pendingQuestions = pendingQuestions;
        this.capabilities = capabilities;
        this.operationLifecycle = operationLifecycle;
    }

    ConversationContextTransitionService(
            ConversationPendingQuestionService pendingQuestions,
            ConversationFocusCapabilityCatalog capabilities) {
        this(pendingQuestions, capabilities, null);
    }

    /** Answers only the two context-control questions; ordinary domain answers pass through. */
    @org.springframework.transaction.annotation.Transactional
    public Optional<IntentResult> answer(
            String text, Runnable beforeMutation, String inboundHmac) {
        if (hasTrustedReference()) return Optional.empty();
        ConversationPendingQuestion pending = pendingQuestions.current().orElse(null);
        String compact = compact(text);
        if (asksCurrentOperationStatus(compact)) {
            if (operationLifecycle == null) return Optional.empty();
            Optional<ConversationOperationLifecycleContributor.Operation> operation =
                    pending == null
                            ? operationLifecycle.describeCurrent()
                            : operationLifecycle.describe(pending);
            if (operation.isEmpty()) {
                return Optional.of(IntentResult.plainMessage(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "目前沒有可辨識的未完成操作。您可以直接告訴我要開始的新操作。"));
            }
            String questionCode = pending == null ? null : pending.getQuestionCode();
            return Optional.of(resumeQuestion(operation.orElseThrow(), questionCode));
        }
        if (isOrphanRoutePending(pending)
                && (isBareContinuation(compact)
                        || asksWhatToSend(compact)
                        || clearsAndRestarts(compact))) {
            return Optional.of(recoverOrphanRoutePending(
                    pending, beforeMutation, inboundHmac));
        }
        if (pending != null && cancelsCurrentOperation(compact)) {
            Optional<ConversationOperationLifecycleContributor.Operation> operation =
                    operationLifecycle == null
                            ? Optional.empty()
                            : operationLifecycle.describe(pending);
            if (operation.isPresent()) {
                beforeMutation.run();
                ConversationOperationLifecycleService.ClearResult canceled =
                        operationLifecycle.closeCurrent(operation.orElseThrow(), inboundHmac);
                if (canceled.resumedParent() != null) {
                    IntentResult resumed = resumeQuestion(canceled.resumedParent(), null);
                    return Optional.of(prependMessage(
                            resumed,
                            "已取消目前的子步驟；原本的「%s」仍保留。"
                                    .formatted(canceled.resumedParent().safeLabel())));
                }
                String message = canceled.domainMutationCount() > 0
                        ? "已取消未完成的路線規劃「%s」。已建立的行程沒有變更。"
                                .formatted(canceled.safeLabel())
                        : "已取消「%s」的附屬設定；已建立的行程沒有變更。"
                                .formatted(canceled.safeLabel());
                return Optional.of(IntentResult.plainMessage(
                        IntentResult.Action.CONTEXT_UPDATED, message));
            }
        }
        if (CURRENT_OPERATION_TREE_CANCEL.contains(compact)) {
            Optional<ConversationOperationLifecycleContributor.Operation> operation =
                    operationLifecycle == null
                            ? Optional.empty()
                            : pending == null
                                    ? operationLifecycle.describeCurrent()
                                    : operationLifecycle.describe(pending);
            if (operation.isPresent()) {
                beforeMutation.run();
                ConversationOperationLifecycleService.ClearResult canceled =
                        operationLifecycle.closeCurrentTree(operation.orElseThrow(), inboundHmac);
                return Optional.of(IntentResult.plainMessage(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "已取消整個未完成的行程規劃「%s」。已建立的行程沒有變更。"
                                .formatted(canceled.safeLabel())));
            }
        }
        if (clearsAndRestarts(compact)) {
            Optional<ConversationOperationLifecycleContributor.Operation> operation =
                    operationLifecycle == null
                            ? Optional.empty()
                            : pending == null
                                    ? operationLifecycle.describeCurrent()
                                    : operationLifecycle.describe(pending);
            if (operation.isPresent()) {
                beforeMutation.run();
                ConversationOperationLifecycleService.ClearResult cleared =
                        operationLifecycle.closeCurrentTree(operation.orElseThrow(), inboundHmac);
                String message = cleared.domainMutationCount() > 0
                        ? "已清除未完成的路線規劃「%s」。已建立的行程沒有變更。請重新告訴我要規劃的路線。"
                                .formatted(cleared.safeLabel())
                        : "已結束「%s」的未完成對話；已建立的行程保持不變。請重新告訴我要規劃的路線。"
                                .formatted(cleared.safeLabel());
                return Optional.of(IntentResult.plainMessage(
                        IntentResult.Action.CONTEXT_UPDATED, message));
            }
            if (pending == null) {
                return Optional.of(IntentResult.plainMessage(
                        IntentResult.Action.CONTEXT_UPDATED,
                        "目前沒有可安全清除的未完成路線規劃。請直接告訴我要開始的新操作。"));
            }
            beforeMutation.run();
            ConversationPendingQuestion changed = TARGET_CODE.equals(pending.getQuestionCode())
                    ? pending
                    : pendingQuestions.beginContextChoice(inboundHmac)
                            .orElseThrow(() -> new IllegalStateException(
                                    "pending context disappeared"));
            return Optional.of(contextQuestion(
                    changed,
                    TARGET_CODE,
                    "這項功能的清除流程尚未接入。要繼續目前的操作，還是保留它並開始新的操作？"));
        }
        if (pending == null) return Optional.empty();
        ConversationOperationLifecycleContributor.Operation operation = operation(pending);
        if (TARGET_CODE.equals(pending.getQuestionCode())) {
            if (choosesContinuation(compact)) {
                beforeMutation.run();
                if (operationLifecycle != null) {
                    operationLifecycle.discardDeferred(pending);
                }
                ConversationPendingQuestion restored = pendingQuestions
                        .resumeInterruptedQuestion(inboundHmac)
                        .orElseThrow(() -> new IllegalStateException(
                                "interrupted question disappeared"));
                return Optional.of(resumeQuestion(
                        operation,
                        restored.getQuestionCode()));
            }
            if (choosesNewOperation(compact)) {
                beforeMutation.run();
                if (operationLifecycle != null && pending.getDeferredWorkflowId() != null) {
                    UUID deferredWorkflowId = pending.getDeferredWorkflowId();
                    Optional<IntentResult> activated = operationLifecycle.activateDeferred(pending);
                    if (activated.isPresent()) {
                        pendingQuestions.completeDeferredNewOperation(
                                deferredWorkflowId, inboundHmac);
                        return Optional.of(withNewOperationNotice(
                                activated.orElseThrow(), operation.safeLabel()));
                    }
                }
                pendingQuestions.requestNewOperationContent(inboundHmac);
                return Optional.of(contextQuestion(
                        pending,
                        NEW_CONTENT_CODE,
                        "原本的「%s」先保留。請告訴我要開始的新操作內容。"
                                .formatted(operation.safeLabel())));
            }
            return Optional.empty();
        }
        if (NEW_CONTENT_CODE.equals(pending.getQuestionCode())
                && choosesContinuation(compact)) {
            beforeMutation.run();
            ConversationPendingQuestion restored = pendingQuestions
                    .resumeInterruptedQuestion(inboundHmac)
                    .orElseThrow(() -> new IllegalStateException(
                            "interrupted question disappeared"));
            return Optional.of(resumeQuestion(
                    operation,
                    restored.getQuestionCode()));
        }
        if (isBareContinuation(compact)
                && operationLifecycle != null
                && !"unmanaged".equals(operation.operationKind())) {
            Optional<ConversationOperationLifecycleContributor.ResumeQuestion> question =
                    operationLifecycle.resumeQuestion(operation, pending.getQuestionCode());
            if (question.isPresent()) {
                beforeMutation.run();
                return Optional.of(resumeQuestion(
                        operation,
                        pending.getQuestionCode()));
            }
        }
        return Optional.empty();
    }

    /** Runs after interpretation and before any domain mutation. */
    @org.springframework.transaction.annotation.Transactional
    public Optional<IntentResult> interceptBeforeExecution(
            String text, IntentCommand command, Runnable beforeMutation, String inboundHmac) {
        if (hasTrustedReference()) return Optional.empty();
        if (command == null || command.type() == null) {
            return Optional.empty();
        }
        ConversationPendingQuestion pending = pendingQuestions.current().orElse(null);
        if (pending == null) {
            return Optional.empty();
        }
        if (command.type() == IntentCommand.Type.UNKNOWN && operationLifecycle != null) {
            Optional<ConversationOperationLifecycleContributor.Operation> current =
                    operationLifecycle.describe(pending);
            if (current.isPresent()
                    && RoutePlaceCreationOperationLifecycleContributor.KIND.equals(
                            current.orElseThrow().operationKind())) {
                IntentResult resumed = resumeQuestion(
                        current.orElseThrow(), pending.getQuestionCode());
                return Optional.of(prependMessage(
                        resumed,
                        "這個指令不是目前階段可處理的答案，因此尚未修改資料。若要改做其他事情，請說「保留，開始新的操作」。"));
            }
        }
        if (capabilities.behaviorFor(command.type()) != FocusBehavior.START_OR_SWITCH) {
            return Optional.empty();
        }
        if (COMPLETED_OPERATION_AUXILIARY_QUESTIONS.contains(pending.getQuestionCode())) {
            beforeMutation.run();
            pendingQuestions.answerCurrent(pending.getQuestionCode());
            return Optional.empty();
        }
        if (command.type() == IntentCommand.Type.PLAN_ROUTE_ITINERARY
                && isOrphanRoutePending(pending)) {
            beforeMutation.run();
            pendingQuestions.cancelCurrent(pending.getWorkflowId(), inboundHmac);
            return Optional.empty();
        }
        if (TARGET_CODE.equals(pending.getQuestionCode())) {
            return Optional.of(contextQuestion(
                    pending, TARGET_CODE, choicePrompt(operation(pending))));
        }
        if (NEW_CONTENT_CODE.equals(pending.getQuestionCode())) {
            return Optional.empty();
        }
        String compact = compact(text);
        if (explicitNewOperation(compact) || explicitContinuation(compact)
                || directlyAnswers(pending.getQuestionCode(), text)) {
            return Optional.empty();
        }
        beforeMutation.run();
        Optional<ConversationOperationLifecycleContributor.Operation> staged =
                operationLifecycle == null
                        ? Optional.empty()
                        : operationLifecycle.stage(command);
        ConversationPendingQuestion changed = pendingQuestions.beginContextChoice(
                        inboundHmac,
                        staged.map(ConversationOperationLifecycleContributor.Operation::workflowId)
                                .orElse(null))
                .orElseThrow(() -> new IllegalStateException("pending context disappeared"));
        return Optional.of(contextQuestion(
                changed, TARGET_CODE, choicePrompt(operation(changed))));
    }

    /** Completes the retained pointer only after a typed new operation executed successfully. */
    public void completeNewOperationIfApplicable(
            String text, IntentCommand command, IntentResult result, Runnable beforeMutation,
            String inboundHmac) {
        if (command == null || command.type() == null || result == null) {
            return;
        }
        ConversationPendingQuestion pending = pendingQuestions.current().orElse(null);
        if (pending == null) return;
        if (NEW_CONTENT_CODE.equals(pending.getQuestionCode())) {
            if (capabilities.behaviorFor(command.type()) != FocusBehavior.START_OR_SWITCH) return;
            if (result.focusBinding() == null && result.task() == null && result.decision() == null) {
                return;
            }
            beforeMutation.run();
            pendingQuestions.completeNewOperationContent(inboundHmac);
            return;
        }
        if (result.nextQuestion() == null
                && directlyAnswers(pending.getQuestionCode(), text)) {
            beforeMutation.run();
            pendingQuestions.answerCurrent(pending.getQuestionCode());
        }
    }

    private IntentResult contextQuestion(
            ConversationPendingQuestion pending, String code, String prompt) {
        ConversationOperationLifecycleContributor.Operation operation = operation(pending);
        return IntentResult.clarificationNeeded(
                        ClarificationStep.blocking(code, "conversationTarget", prompt, 1))
                .withFocusDirective(binding(operation), ConversationFocusDirective.FOCUS_CONTROL_ONLY);
    }

    private static String choicePrompt(
            ConversationOperationLifecycleContributor.Operation operation) {
        return "您目前還有「%s」尚未完成。這次指令要繼續目前操作，還是保留目前進度並開始新的操作？"
                .formatted(operation.safeLabel());
    }

    private static ConversationFocusBinding binding(
            ConversationOperationLifecycleContributor.Operation operation) {
        return ConversationFocusBinding.workflow(
                operation.rootDomain().toUpperCase(Locale.ROOT),
                operation.workflowId(),
                operation.safeLabel());
    }

    private static IntentResult withNewOperationNotice(
            IntentResult result, String retainedSafeLabel) {
        String currentSafeLabel = result.focusBinding() == null
                ? "新的操作"
                : result.focusBinding().safeLabel();
        return new IntentResult(
                result.action(),
                "原本的「%s」先保留。現在改處理「%s」。\n\n%s"
                        .formatted(retainedSafeLabel, currentSafeLabel, result.message()),
                result.task(),
                result.decision(),
                result.focusNotice(),
                result.focusBinding(),
                result.focusDirective(),
                result.nextQuestion());
    }

    private IntentResult resumeQuestion(
            ConversationOperationLifecycleContributor.Operation operation,
            String currentQuestionCode) {
        Optional<ConversationOperationLifecycleContributor.ResumeQuestion> resumed =
                operationLifecycle == null
                        ? Optional.empty()
                        : operationLifecycle.resumeQuestion(operation, currentQuestionCode);
        if (resumed.isEmpty()) {
            return IntentResult.plainMessage(
                            IntentResult.Action.CLARIFICATION_NEEDED,
                            "目前正在處理：\n🏷️ %s\n\n目前進度：\n目前無法安全還原下一個步驟。\n\n下一步：\n您可以說「全部清除重來」，或重新補充這項操作需要的資訊。"
                                    .formatted(operation.safeLabel()))
                    .withFocusDirective(
                            binding(operation), ConversationFocusDirective.FOCUS_CONTROL_ONLY);
        }
        ConversationOperationLifecycleContributor.ResumeQuestion question = resumed.orElseThrow();
        if (question.choiceQuestion() != null) {
            return IntentResult.choiceNeeded(
                            renderResumeContext(question), question.choiceQuestion())
                    .withFocusDirective(
                            binding(operation), ConversationFocusDirective.FOCUS_CONTROL_ONLY);
        }
        return IntentResult.clarificationNeeded(
                        renderResumeContext(question),
                        ClarificationStep.blocking(
                                question.code(), question.slot(), question.prompt(),
                                question.maxLength()))
                .withFocusDirective(
                        binding(operation), ConversationFocusDirective.FOCUS_CONTROL_ONLY);
    }

    private static String renderResumeContext(
            ConversationOperationLifecycleContributor.ResumeQuestion question) {
        ConversationOperationLifecycleContributor.LifecycleContext context =
                question.lifecycleContext();
        return "目前正在處理：\n🏷️ %s\n\n目前進度：\n目前子步驟：%s\n\n已保留：\n%s\n\n尚缺：\n%s\n\n下一步："
                .formatted(
                        context.publicTopic(),
                        context.activeStep(),
                        renderFacts(context.preservedFacts()),
                        renderFacts(context.unresolvedFacts()));
    }

    private static String renderFacts(java.util.List<String> facts) {
        return facts.stream().map(fact -> "- " + fact).collect(java.util.stream.Collectors.joining("\n"));
    }

    private ConversationOperationLifecycleContributor.Operation operation(
            ConversationPendingQuestion pending) {
        if (operationLifecycle != null) {
            Optional<ConversationOperationLifecycleContributor.Operation> resolved =
                    operationLifecycle.describe(pending);
            if (resolved.isPresent()) return resolved.orElseThrow();
        }
        String safeLabel = pending.getWorkflowSafeLabel();
        if (safeLabel == null || safeLabel.isBlank()) safeLabel = "目前未完成的操作";
        return new ConversationOperationLifecycleContributor.Operation(
                "unmanaged",
                pending.getRootDomain(),
                pending.getWorkflowId(),
                null,
                safeLabel,
                false);
    }

    private static boolean choosesContinuation(String compact) {
        return matchesAny(compact,
                "繼續", "繼續完成", "繼續處理", "延續", "原本", "舊的", "上一個")
                || containsAny(compact,
                "繼續原本", "繼續舊", "延續原本", "延續舊", "原本的", "上一個",
                "不要新的", "取消新的", "回到剛才");
    }

    private static boolean isBareContinuation(String compact) {
        return matchesAny(
                compact, "繼續", "繼續完成", "繼續處理", "延續", "原本", "舊的", "上一個");
    }

    private boolean isOrphanRoutePending(ConversationPendingQuestion pending) {
        return pending != null
                && pending.getQuestionCode().startsWith("route.")
                && operationLifecycle != null
                && operationLifecycle.describe(pending).isEmpty();
    }

    private IntentResult recoverOrphanRoutePending(
            ConversationPendingQuestion pending,
            Runnable beforeMutation,
            String inboundHmac) {
        beforeMutation.run();
        if (operationLifecycle != null) {
            operationLifecycle.discardCurrentUnfinished("route");
        }
        if (!pendingQuestions.cancelCurrent(pending.getWorkflowId(), inboundHmac)) {
            throw new IllegalStateException("orphan route pending changed concurrently");
        }
        return IntentResult.clarificationNeeded(
                "先前的路線規劃已失效，行事曆沒有新增資料。",
                ClarificationStep.blocking(
                        "route.new-request",
                        "route.request",
                        "請重新告訴我要規劃的起點、目的地與時間。",
                        200));
    }

    private static boolean asksWhatToSend(String compact) {
        return matchesAny(compact,
                "傳什麼", "要傳什麼", "要怎麼繼續", "接下來呢", "下一步");
    }

    private static IntentResult prependMessage(IntentResult result, String prefix) {
        return new IntentResult(
                result.action(),
                prefix + "\n\n" + result.message(),
                result.task(),
                result.decision(),
                result.focusNotice(),
                result.focusBinding(),
                result.focusDirective(),
                result.nextQuestion());
    }

    private static boolean asksCurrentOperationStatus(String compact) {
        boolean directProgressQuestion = containsAny(
                compact,
                "處理到哪",
                "做到哪",
                "進度到哪",
                "進度如何",
                "進度怎樣",
                "進度怎麼樣",
                "在處理什麼",
                "在忙什麼");
        if (directProgressQuestion) return true;

        boolean currentContext = compact.startsWith("現在")
                || compact.startsWith("目前")
                || compact.startsWith("當前");
        boolean operation = containsAny(compact, "處理", "操作", "項目", "進度", "做到", "忙");
        boolean question = containsAny(compact, "哪裡", "哪個", "什麼", "如何", "狀況", "怎樣");
        return currentContext && operation && question;
    }

    private static boolean choosesNewOperation(String compact) {
        return matchesAny(compact, "新的", "新", "新操作", "另開", "另外一個")
                || containsAny(compact,
                "開始新的", "新的操作", "新操作", "另外一個", "另一個", "改做新的");
    }

    private static boolean clearsAndRestarts(String compact) {
        return CLEAR_AND_RESTART.contains(compact);
    }

    private static boolean cancelsCurrentOperation(String compact) {
        return CURRENT_OPERATION_CANCEL.contains(compact);
    }

    private static boolean explicitNewOperation(String compact) {
        return containsAny(compact,
                "另外", "另一個操作", "另一個行程", "另一個任務", "另一份草稿",
                "再新增", "另建", "新增一個", "開始新的", "新操作");
    }

    private static boolean explicitContinuation(String compact) {
        return containsAny(compact,
                "改成", "修改", "更正", "調整", "延後", "提前", "設為", "補上", "前緩衝",
                "後緩衝", "前面", "後面", "上一題", "剛才", "原本");
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) return true;
        }
        return false;
    }

    private static boolean matchesAny(String text, String... values) {
        for (String value : values) {
            if (text.equals(value)) return true;
        }
        return false;
    }

    private static boolean directlyAnswers(String questionCode, String text) {
        if (questionCode == null || text == null) return false;
        if (questionCode.endsWith(".recurrence-weekday")) {
            return WEEKDAY.matcher(text).find();
        }
        if (questionCode.endsWith(".recurrence-time")
                || questionCode.endsWith(".exact-time")) {
            return EXACT_TIME.matcher(text).find();
        }
        return false;
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("[\\s，,。.!！?？：:；;]", "");
    }

    private static boolean hasTrustedReference() {
        return TrustedConversationReferenceContext.currentDraftId() != null
                || TrustedConversationReferenceContext.currentMaterializationProposalId() != null
                || TrustedConversationReferenceContext.currentMediaId() != null;
    }
}
