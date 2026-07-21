package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.handler.IntentHandlerRegistry;
import java.util.Objects;
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

    private ResolvedFocusExecution<IntentResult> resolve(String text, IntentCommand command) {
        FocusBehavior behavior = focusIntentHandler.behaviorFor(command.type());
        boolean hadActiveFocus = focusService.hasActiveFocus();
        ConversationFocus previous = focusService.activeFocus().orElse(null);
        IntentResult result = handlers.dispatch(text, command);
        if (behavior != FocusBehavior.START_OR_SWITCH) {
            return ResolvedFocusExecution.keep(result);
        }
        return targets.resolve(command, result)
                .map(target -> transition(command, result, target, hadActiveFocus, previous))
                .orElseGet(() -> ResolvedFocusExecution.keep(result));
    }

    private ResolvedFocusExecution<IntentResult> transition(IntentCommand command, IntentResult result,
                                                             ConversationFocusTargetResolver.ResourceTarget target,
                                                             boolean hadActiveFocus,
                                                             ConversationFocus previous) {
        if (!contributors.require(target.domain()).isAvailable(target)) {
            throw new IllegalStateException("focus target is unavailable");
        }
        FocusControl control = hadActiveFocus
                ? new FocusControl.SwitchResource(target.domain(), target.routingKey(), target.safeLabel())
                : new FocusControl.EnterResource(target.domain(), target.routingKey(), target.safeLabel());
        FocusDecision decision = focusIntentHandler.decide(command.type(), control, hadActiveFocus);
        if (!(decision instanceof FocusDecision.Transition transition)) {
            return ResolvedFocusExecution.keep(result);
        }
        FocusTransitionNotice notice = FocusTransitionNotice.forTransition(transition.type(),
                transition.type() == FocusTransitionType.SWITCH ? previous.getSafeLabel() : null,
                target.safeLabel(), null);
        return new ResolvedFocusExecution<>(result.withFocusNotice(notice), decision, control, notice);
    }
}
