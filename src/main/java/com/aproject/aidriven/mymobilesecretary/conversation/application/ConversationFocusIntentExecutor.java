package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Bridges typed intent handlers to the durable focus model without inspecting free-form text. */
@Service
public final class ConversationFocusIntentExecutor {

    private final IntentHandlerRegistry handlers;
    private final ConversationFocusIntentHandler focusIntentHandler;
    private final ConversationFocusTargetResolver targets;
    private final ConversationFocusContributorRegistry contributors;
    private final ConversationFocusService focusService;
    private final ConversationFocusAtomicExecutor atomicExecutor;

    public ConversationFocusIntentExecutor(IntentHandlerRegistry handlers,
                                           ConversationFocusIntentHandler focusIntentHandler,
                                           ConversationFocusTargetResolver targets,
                                           ConversationFocusContributorRegistry contributors,
                                           ConversationFocusService focusService,
                                           ConversationFocusAtomicExecutor atomicExecutor) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.focusIntentHandler = Objects.requireNonNull(focusIntentHandler, "focusIntentHandler");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.contributors = Objects.requireNonNull(contributors, "contributors");
        this.focusService = Objects.requireNonNull(focusService, "focusService");
        this.atomicExecutor = Objects.requireNonNull(atomicExecutor, "atomicExecutor");
    }

    public IntentResult execute(String text, IntentCommand command, String inboundHmac) {
        Objects.requireNonNull(command, "command");
        return atomicExecutor.executeResolved(inboundHmac, () -> resolve(text, command));
    }

    /** Coordinates a bounded non-LLM fallback after the interpreter has failed. */
    public Optional<IntentResult> executeResolved(
            String inboundHmac,
            Supplier<Optional<IntentResult>> domainMutation,
            Function<IntentResult, IntentCommand.Type> typeResolver) {
        Objects.requireNonNull(domainMutation, "domainMutation");
        Objects.requireNonNull(typeResolver, "typeResolver");
        return atomicExecutor.executeResolved(inboundHmac, () -> {
            Optional<IntentResult> result = domainMutation.get();
            if (result.isEmpty()) {
                return ResolvedFocusExecution.keep(Optional.empty());
            }
            IntentResult response = result.orElseThrow();
            ResolvedFocusExecution<IntentResult> resolved = resolve(
                    Objects.requireNonNull(typeResolver.apply(response), "resolved type"), response);
            return new ResolvedFocusExecution<>(Optional.of(resolved.response()),
                    resolved.decision(), resolved.control(), resolved.notice());
        });
    }

    private ResolvedFocusExecution<IntentResult> resolve(String text, IntentCommand command) {
        IntentResult result = handlers.dispatch(text, command);
        if (focusIntentHandler.behaviorFor(command.type()) == FocusBehavior.CONTROL) {
            return control(command.type(), result, focusService.activeFocus().orElse(null));
        }
        return resolve(command.type(), result);
    }

    private ResolvedFocusExecution<IntentResult> control(
            IntentCommand.Type type, IntentResult result, ConversationFocus previous) {
        if (previous == null) {
            return ResolvedFocusExecution.keep(IntentResult.clarificationNeeded(
                            "目前沒有正在處理的事項；這次沒有變更任何資料。")
                    .withFocusDirective(ConversationFocusDirective.FOCUS_CONTROL_ONLY));
        }
        FocusControl control = switch (type) {
            case EXIT_CONVERSATION_FOCUS -> FocusControl.Exit.INSTANCE;
            case CLOSE_CONVERSATION_FOCUS -> FocusControl.Close.INSTANCE;
            default -> throw new IllegalStateException("unsupported focus control " + type);
        };
        FocusDecision decision = focusIntentHandler.decide(type, control, true);
        if (!(decision instanceof FocusDecision.Transition transition)) {
            throw new IllegalStateException("explicit focus control did not produce a transition");
        }
        FocusTransitionNotice notice = FocusTransitionNotice.forTransition(
                transition.type(), null, previous.getSafeLabel(), null);
        return new ResolvedFocusExecution<>(
                result.withFocusNotice(notice), decision, control, notice);
    }

    private ResolvedFocusExecution<IntentResult> resolve(IntentCommand.Type type, IntentResult result) {
        FocusBehavior behavior = focusIntentHandler.behaviorFor(type);
        boolean hadActiveFocus = focusService.hasActiveFocus();
        ConversationFocus previous = focusService.activeFocus().orElse(null);
        if (behavior == FocusBehavior.TERMINAL) {
            return terminal(type, result, previous);
        }
        if (behavior != FocusBehavior.START_OR_SWITCH) {
            return ResolvedFocusExecution.keep(result);
        }
        return targets.resolve(result)
                .map(target -> transition(type, result, target, hadActiveFocus, previous))
                .orElseGet(() -> ResolvedFocusExecution.keep(result));
    }

    private ResolvedFocusExecution<IntentResult> transition(IntentCommand.Type type, IntentResult result,
                                                             ConversationFocusTargetResolver.ResourceTarget target,
                                                             boolean hadActiveFocus,
                                                             ConversationFocus previous) {
        if (!target.workflow() && !contributors.require(target.domain()).isAvailable(target)) {
            throw new IllegalStateException("focus target is unavailable");
        }
        if (sameTarget(previous, target)) {
            return changeSubfocus(type, result, target, previous);
        }
        var suspended = target.workflow()
                ? Optional.<ConversationFocus>empty()
                : focusService.suspendedResource(target.domain(), target.routingKey());
        if (suspended.isPresent()) {
            FocusControl.Resume control = new FocusControl.Resume(
                    suspended.orElseThrow().getId(), target.safeLabel());
            FocusDecision decision = focusIntentHandler.decide(
                    type, control, hadActiveFocus);
            if (!(decision instanceof FocusDecision.Transition transition)) {
                throw new IllegalStateException("suspended focus target could not be resumed");
            }
            FocusTransitionNotice notice = FocusTransitionNotice.forTransition(
                    transition.type(), previous == null ? null : previous.getSafeLabel(),
                    target.safeLabel(), null);
            return new ResolvedFocusExecution<>(
                    result.withFocusNotice(notice), decision, control, notice);
        }
        FocusControl control;
        if (target.workflow()) {
            control = hadActiveFocus
                    ? new FocusControl.SwitchWorkflow(
                            target.domain(), target.workflowId(), target.safeLabel())
                    : new FocusControl.EnterWorkflow(
                            target.domain(), target.workflowId(), target.safeLabel());
        } else {
            control = hadActiveFocus
                    ? new FocusControl.SwitchResource(
                            target.domain(), target.routingKey(), target.safeLabel())
                    : new FocusControl.EnterResource(
                            target.domain(), target.routingKey(), target.safeLabel());
        }
        FocusDecision decision = focusIntentHandler.decide(type, control, hadActiveFocus);
        if (!(decision instanceof FocusDecision.Transition transition)) {
            return ResolvedFocusExecution.keep(result);
        }
        FocusTransitionNotice notice = FocusTransitionNotice.forTransition(transition.type(),
                transition.type() == FocusTransitionType.SWITCH ? previous.getSafeLabel() : null,
                target.safeLabel(), null);
        return new ResolvedFocusExecution<>(result.withFocusNotice(notice), decision, control, notice);
    }

    private ResolvedFocusExecution<IntentResult> changeSubfocus(
            IntentCommand.Type type, IntentResult result,
            ConversationFocusTargetResolver.ResourceTarget target,
            ConversationFocus previous) {
        if (target.activityCode() == null
                || Objects.equals(previous.getActivityCode(), target.activityCode())) {
            return ResolvedFocusExecution.keep(result);
        }
        FocusControl.ChangeSubfocus control = new FocusControl.ChangeSubfocus(
                target.activityCode(), target.activityLabel());
        FocusDecision decision = focusIntentHandler.decide(type, control, true);
        if (!(decision instanceof FocusDecision.Transition transition)) {
            throw new IllegalStateException("changed workflow activity did not produce a transition");
        }
        FocusTransitionNotice notice = FocusTransitionNotice.forTransition(
                transition.type(), null, target.safeLabel(), target.activityLabel());
        return new ResolvedFocusExecution<>(
                result.withFocusNotice(notice), decision, control, notice);
    }

    private ResolvedFocusExecution<IntentResult> terminal(IntentCommand.Type type, IntentResult result,
                                                           ConversationFocus previous) {
        if (result.focusDirective() == null) {
            return ResolvedFocusExecution.keep(result);
        }
        ConversationFocusTargetResolver.ResourceTarget target = targets.resolve(result)
                .orElseThrow(() -> new IllegalStateException(
                        "terminal focus directive requires a typed target"));
        if (result.focusDirective() != ConversationFocusDirective.INVALIDATE_TARGET
                || previous == null) {
            return ResolvedFocusExecution.keep(result);
        }
        FocusControl control;
        String retainedLabel = null;
        if (sameTarget(previous, target)) {
            control = FocusControl.Invalidate.INSTANCE;
        } else {
            Optional<ConversationFocus> suspended = focusService.suspendedResource(
                    target.domain(), target.routingKey());
            if (suspended.isEmpty()) {
                return ResolvedFocusExecution.keep(result);
            }
            control = new FocusControl.InvalidateTarget(suspended.orElseThrow().getId());
            retainedLabel = previous.getSafeLabel();
        }
        FocusDecision decision = focusIntentHandler.decide(type, control, true);
        if (!(decision instanceof FocusDecision.Transition transition)) {
            throw new IllegalStateException("active terminal target could not be invalidated");
        }
        FocusTransitionNotice notice = FocusTransitionNotice.forTransition(
                transition.type(), retainedLabel, target.safeLabel(), null);
        return new ResolvedFocusExecution<>(
                result.withFocusNotice(notice), decision, control, notice);
    }

    private static boolean sameTarget(ConversationFocus previous,
                                      ConversationFocusTargetResolver.ResourceTarget target) {
        if (previous == null || !previous.getRootDomain().equals(target.domain())) {
            return false;
        }
        return target.workflow()
                ? Objects.equals(previous.getWorkflowId(), target.workflowId())
                : Objects.equals(previous.getRoutingKey(), target.routingKey());
    }
}
