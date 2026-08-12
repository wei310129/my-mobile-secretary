package com.aproject.aidriven.mymobilesecretary.intent.application;

import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Final public boundary, invoked after every formatter and decorator and before adapter output. */
@Service
public class PublicConversationResponseService {

    public PublicConversationReply finalizeReply(IntentResult result) {
        Set<PublicReplyEvidence> evidence = new HashSet<>(
                SecretaryReplyContractCatalog.forAction(result.action()).defaultEvidence());
        evidence.addAll(result.publicReplyEvidence());
        if (result.nextQuestion() != null) {
            evidence.add(PublicReplyEvidence.PENDING_COMMITTED);
        }
        String decorated = result.responsePresentation()
                        == IntentResult.ResponsePresentation.PLAIN
                ? IntentReplyFormatter.formatPlain(result.responseEnvelope().message())
                : IntentReplyFormatter.format(
                        result.action(), result.responseEnvelope().message());
        return validate(new PublicConversationReply(
                decorated, result.nextQuestion() == null
                        ? terminalState(result.action())
                        : PublicConversationReply.TerminalState.NEEDS_INPUT,
                result.nextQuestion(), evidence));
    }

    public PublicConversationReply finalizeReply(PublicConversationReply reply) {
        String decorated = IntentReplyFormatter.formatPlain(reply.message());
        return validate(new PublicConversationReply(
                decorated, reply.terminalState(), reply.nextQuestion(), reply.evidence()));
    }

    private static PublicConversationReply validate(PublicConversationReply reply) {
        String tonedMessage = SecretaryReplyTonePolicy.normalize(reply.message());
        String truthfulMessage = TruthfulCommitmentPolicy.enforce(tonedMessage, reply.evidence());
        String safeMessage = UserReplySafetyPolicy.sanitize(truthfulMessage);
        safeMessage = SingleQuestionPolicy.enforce(safeMessage, reply.nextQuestion());
        return new PublicConversationReply(
                safeMessage, reply.terminalState(), reply.nextQuestion(), reply.evidence());
    }

    public PublicConversationReply finalizeMessage(
            String message, PublicConversationReply.TerminalState terminalState) {
        return finalizeReply(PublicConversationReply.terminal(message, terminalState));
    }

    public PublicConversationReply finalizeMessage(
            String message, PublicConversationReply.TerminalState terminalState,
            PublicReplyEvidence... evidence) {
        return finalizeReply(PublicConversationReply.terminal(message, terminalState, evidence));
    }

    public PublicConversationReply.TerminalState terminalState(String action) {
        try {
            return terminalState(IntentResult.Action.valueOf(action));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return PublicConversationReply.TerminalState.FAILED;
        }
    }

    private static PublicConversationReply.TerminalState terminalState(IntentResult.Action action) {
        return switch (action) {
            case CLARIFICATION_NEEDED,
                    SCHEDULE_NEEDS_DECISION,
                    CALENDAR_ATTACHMENT_DELETE_CONFIRMATION_REQUIRED,
                    SCHEDULE_CANCELLATION_PREVIEWED ->
                PublicConversationReply.TerminalState.NEEDS_INPUT;
            case AI_UNAVAILABLE -> PublicConversationReply.TerminalState.FAILED;
            default -> PublicConversationReply.TerminalState.SUCCEEDED;
        };
    }
}
