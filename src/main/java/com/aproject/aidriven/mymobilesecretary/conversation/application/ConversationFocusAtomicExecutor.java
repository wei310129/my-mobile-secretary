package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocusCloseReason;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs a domain callback and its focus write in one transaction; failures never produce a reply. */
@Service
public class ConversationFocusAtomicExecutor {

    private final ConversationFocusService focusService;
    private final ConversationFocusReplyDecorator replyDecorator;

    public ConversationFocusAtomicExecutor(ConversationFocusService focusService,
                                           ConversationFocusReplyDecorator replyDecorator) {
        this.focusService = Objects.requireNonNull(focusService, "focusService");
        this.replyDecorator = Objects.requireNonNull(replyDecorator, "replyDecorator");
    }

    @Transactional
    public FocusResponseEnvelope execute(FocusDecision decision, FocusControl control,
                                         String inboundHmac, FocusTransitionNotice notice,
                                         Supplier<FocusResponseEnvelope> domainMutation) {
        FocusResponseEnvelope reply = Objects.requireNonNull(domainMutation, "domainMutation").get();
        if (decision instanceof FocusDecision.Transition transition) {
            requireMatchingNotice(transition.type(), notice);
            if (reply.notice() != null) {
                throw new IllegalStateException("domain mutation must not pre-render a focus notice");
            }
            apply(transition.type(), Objects.requireNonNull(control, "control"), inboundHmac);
            return FocusResponseEnvelope.withNotice(reply.message(), notice, replyDecorator);
        }
        if (notice != null) {
            throw new IllegalStateException("only a focus transition may render a focus notice");
        }
        return reply;
    }

    /** Applies a focus decision derived from the committed domain result in the same transaction. */
    @Transactional
    public <T> T executeResolved(String inboundHmac,
                                 Supplier<ResolvedFocusExecution<T>> domainMutation) {
        ResolvedFocusExecution<T> resolved = Objects.requireNonNull(
                domainMutation, "domainMutation").get();
        if (resolved.decision() instanceof FocusDecision.Transition transition) {
            requireMatchingNotice(transition.type(), resolved.notice());
            apply(transition.type(), resolved.control(), inboundHmac);
        } else if (resolved.notice() != null) {
            throw new IllegalStateException("only a focus transition may render a focus notice");
        }
        return resolved.response();
    }

    private static void requireMatchingNotice(FocusTransitionType type, FocusTransitionNotice notice) {
        if (notice == null || notice.type() != type) {
            throw new IllegalStateException("focus transition requires a matching public notice");
        }
    }

    private void apply(FocusTransitionType type, FocusControl control, String inboundHmac) {
        switch (type) {
            case ENTER -> {
                if (control instanceof FocusControl.EnterWorkflow enter) {
                    requirePersisted(focusService.enterWorkflow(
                            enter.domain(), enter.workflowId(), enter.safeLabel(), inboundHmac));
                } else if (control instanceof FocusControl.EnterResource enter) {
                    requirePersisted(focusService.enterResource(
                            enter.domain(), enter.routingKey(), enter.safeLabel(), inboundHmac));
                } else {
                    FocusControl.EnterAsyncWork async = require(control,
                            FocusControl.EnterAsyncWork.class, type);
                    requirePersisted(focusService.enterAsyncWork(
                            async.domain(), async.workflowId(), async.safeLabel(), inboundHmac));
                }
            }
            case CHANGE_SUBFOCUS -> {
                FocusControl.ChangeSubfocus change = require(
                        control, FocusControl.ChangeSubfocus.class, type);
                focusService.changeActivity(change.code(), change.safeLabel(), inboundHmac);
            }
            case SWITCH -> {
                if (control instanceof FocusControl.SwitchWorkflow change) {
                    requirePersisted(focusService.switchWorkflow(
                            change.domain(), change.workflowId(), change.safeLabel(), inboundHmac));
                } else {
                    FocusControl.SwitchResource change = require(
                            control, FocusControl.SwitchResource.class, type);
                    requirePersisted(focusService.switchResource(
                            change.domain(), change.routingKey(), change.safeLabel(), inboundHmac));
                }
            }
            case RESUME -> focusService.resume(require(control, FocusControl.Resume.class, type).focusId(),
                    inboundHmac);
            case EXIT -> {
                require(control, FocusControl.Exit.class, type);
                focusService.exit(inboundHmac);
            }
            case CLOSE -> {
                require(control, FocusControl.Close.class, type);
                focusService.close(ConversationFocusCloseReason.USER_CLOSED, inboundHmac);
            }
            case INVALIDATE -> {
                require(control, FocusControl.Invalidate.class, type);
                focusService.invalidate(inboundHmac);
            }
        }
    }

    private static <T extends FocusControl> T require(FocusControl control, Class<T> expected,
                                                       FocusTransitionType type) {
        if (!expected.isInstance(control)) {
            throw new IllegalStateException("focus control does not match transition " + type);
        }
        return expected.cast(control);
    }

    private static void requirePersisted(ConversationFocus focus) {
        if (focus == null) {
            throw new IllegalStateException("focus transition did not persist");
        }
    }
}
