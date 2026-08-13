package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationPendingQuestion;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Bounded Java policy for common conversation-lifecycle controls.
 *
 * <p>It does not interpret an intent, call an LLM, or render a LINE reply. The Intent/LINE slice
 * consumes its typed outcome and owns public wording.</p>
 */
@Service
public class ConversationContextTransitionService {

    private static final Set<String> CLEAR_AND_RESTART = Set.of(
            "全部清除重來", "清除重來", "全部取消重來", "全部重來");
    private static final Set<String> CANCEL_CURRENT = Set.of(
            "取消", "取消目前", "取消這個", "不要了", "結束這個");
    private static final Set<String> CONTINUE = Set.of(
            "繼續", "繼續處理", "繼續這個", "延續", "延續這個");
    private static final Set<String> NEW_OPERATION = Set.of(
            "新的", "新操作", "開始新的", "開始新的操作", "開始新操作", "改做新的");
    private static final Set<String> STATUS = Set.of(
            "現在處理到哪裡", "現在處理到哪", "現在在處理哪個項目", "現在在處理什麼",
            "目前處理到哪裡", "目前在哪個階段", "目前進度");

    private final ConversationPendingQuestionService pendingQuestions;
    private final ConversationOperationLifecycleService lifecycle;

    public ConversationContextTransitionService(ConversationPendingQuestionService pendingQuestions,
                                                ConversationOperationLifecycleService lifecycle) {
        this.pendingQuestions = pendingQuestions;
        this.lifecycle = lifecycle;
    }

    public Optional<Transition> intercept(String inboundText, String inboundHmac) {
        String compact = compact(inboundText);
        if (STATUS.contains(compact)) {
            return lifecycle.resumeQuestion().map(question -> Transition.status(question.lifecycleContext()));
        }
        if (CLEAR_AND_RESTART.contains(compact)) {
            ConversationOperationLifecycleService.ClearResult result = lifecycle.closeCurrent(inboundHmac);
            return Optional.of(Transition.clearAndRestart(result));
        }
        if (CANCEL_CURRENT.contains(compact)) {
            ConversationOperationLifecycleService.ClearResult result = lifecycle.closeCurrent(inboundHmac);
            return Optional.of(Transition.cancelCurrent(result));
        }

        Optional<ConversationPendingQuestion> current = pendingQuestions.current();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        ConversationPendingQuestion pending = current.get();
        if (CONTINUE.contains(compact)) {
            if (pending.getQuestionCode().equals("conversation.context-target")
                    || pending.getQuestionCode().equals("conversation.new-operation-content")) {
                return pendingQuestions.resumeInterruptedQuestion(inboundHmac)
                        .map(ignored -> Transition.resumeInterrupted());
            }
            return lifecycle.resumeQuestion().map(question -> Transition.continueCurrent(
                    question.lifecycleContext()));
        }
        if (NEW_OPERATION.contains(compact)) {
            if (pending.getQuestionCode().equals("conversation.context-target")) {
                UUID deferred = pending.getDeferredWorkflowId();
                if (deferred != null) {
                    return Optional.of(Transition.activateDeferred(deferred));
                }
                return pendingQuestions.requestNewOperationContent(inboundHmac)
                        .map(ignored -> Transition.requestNewOperationContent());
            }
            return pendingQuestions.beginContextChoice(inboundHmac, null)
                    .map(ignored -> Transition.chooseContext());
        }
        return Optional.empty();
    }

    private static String compact(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？、]", "");
    }

    public record Transition(Action action,
                             ConversationOperationLifecycleContributor.LifecycleContext lifecycleContext,
                             UUID deferredWorkflowId,
                             ConversationOperationLifecycleService.ClearResult clearResult) {
        static Transition status(ConversationOperationLifecycleContributor.LifecycleContext context) {
            return new Transition(Action.STATUS, context, null, null);
        }

        static Transition continueCurrent(ConversationOperationLifecycleContributor.LifecycleContext context) {
            return new Transition(Action.CONTINUE_CURRENT, context, null, null);
        }

        static Transition chooseContext() {
            return new Transition(Action.CHOOSE_CONTEXT, null, null, null);
        }

        static Transition requestNewOperationContent() {
            return new Transition(Action.REQUEST_NEW_OPERATION_CONTENT, null, null, null);
        }

        static Transition activateDeferred(UUID workflowId) {
            return new Transition(Action.ACTIVATE_DEFERRED_OPERATION, null, workflowId, null);
        }

        static Transition resumeInterrupted() {
            return new Transition(Action.RESUME_INTERRUPTED, null, null, null);
        }

        static Transition clearAndRestart(ConversationOperationLifecycleService.ClearResult result) {
            return new Transition(Action.CLEAR_AND_RESTART, null, null, result);
        }

        static Transition cancelCurrent(ConversationOperationLifecycleService.ClearResult result) {
            return new Transition(Action.CANCEL_CURRENT, null, null, result);
        }
    }

    public enum Action {
        STATUS,
        CONTINUE_CURRENT,
        CHOOSE_CONTEXT,
        REQUEST_NEW_OPERATION_CONTENT,
        ACTIVATE_DEFERRED_OPERATION,
        RESUME_INTERRUPTED,
        CLEAR_AND_RESTART,
        CANCEL_CURRENT
    }
}
