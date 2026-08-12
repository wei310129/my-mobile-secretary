package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.security.idempotency.IdempotencyService;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.FocusTransitionType;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.PublicConversationReply;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Reserves one actor-scoped conversation inbound and replays its encrypted terminal result. */
@Service
public final class ConversationIntentReplayService {

    private static final Logger log = LoggerFactory.getLogger(ConversationIntentReplayService.class);
    private static final String FINGERPRINT_VERSION = "conversation-intent-v1";

    private final IdempotencyService idempotencyService;
    private final ConversationScopeResolver scopeResolver;
    private final ObjectMapper objectMapper;

    public ConversationIntentReplayService(IdempotencyService idempotencyService,
                                           ConversationScopeResolver scopeResolver,
                                           ObjectMapper objectMapper) {
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService");
        this.scopeResolver = Objects.requireNonNull(scopeResolver, "scopeResolver");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public Attempt begin(UUID requestId, String userText, String interpretationText) {
        WorkspaceContext context = WorkspaceContextHolder.current().orElse(null);
        if (context == null || requestId == null) {
            return Attempt.disabled();
        }
        String channel = "INTENT_" + context.channel().name();
        String key = scopeResolver.current(context).digest() + ":" + requestId;
        String fingerprint = String.join("\u0000", FINGERPRINT_VERSION,
                normalize(userText), normalize(interpretationText));
        IdempotencyService.BeginResult reservation = idempotencyService.begin(
                context.workspaceId(), context.actorId(), channel, key, fingerprint);
        return switch (reservation.state()) {
            case NEW -> Attempt.reserved(
                    idempotencyService, objectMapper, context, channel, key);
            case REPLAY_AVAILABLE -> Attempt.replay(replay(reservation));
            case IN_PROGRESS -> Attempt.replay(safe(
                    "這則訊息仍在處理中，我不會重複執行。"));
            case RESULT_UNKNOWN -> Attempt.replay(safe(
                    "先前的處理結果目前無法確認；為避免重複修改，這次沒有再次執行。"));
            case COMPLETED_NO_REPLAY -> Attempt.replay(completedWithoutBody(reservation));
            case PREVIOUS_FAILED -> Attempt.replay(safe(
                    "先前的處理沒有完成，這次也沒有變更資料；請重新傳送一次。"));
            case CONFLICT -> Attempt.replay(safe(
                    "這則訊息與先前收到的內容不一致；已停止處理，這次沒有建立或修改資料。"));
        };
    }

    private IntentResult replay(IdempotencyService.BeginResult reservation) {
        IntentResult.Action action = action(reservation.responseAction())
                .orElse(IntentResult.Action.CLARIFICATION_NEEDED);
        try {
            StoredReply stored = objectMapper.readValue(
                    reservation.responseBody(), StoredReply.class);
            if ((stored.version() != 1 && stored.version() != 2) || stored.message() == null) {
                return safe("先前的處理已完成，但回覆資料無法驗證；為避免重複修改，這次沒有再次執行。");
            }
            IntentResult result = IntentResult.message(action, stored.message());
            if (stored.version() == 2 && stored.question() != null) {
                result = result.withNextQuestion(stored.question().toQuestion());
            }
            return stored.notice() == null ? result : result.withFocusNotice(stored.notice().toNotice());
        } catch (JsonProcessingException invalid) {
            return safe("先前的處理已完成，但回覆資料無法驗證；為避免重複修改，這次沒有再次執行。");
        }
    }

    private static IntentResult completedWithoutBody(IdempotencyService.BeginResult reservation) {
        IntentResult.Action action = action(reservation.responseAction())
                .orElse(IntentResult.Action.CLARIFICATION_NEEDED);
        return IntentResult.message(action,
                "這則訊息先前已處理完成；為避免重複修改，這次沒有再次執行。請查看目前資料確認結果。");
    }

    private static Optional<IntentResult.Action> action(String value) {
        try {
            return value == null ? Optional.empty()
                    : Optional.of(IntentResult.Action.valueOf(value));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static IntentResult safe(String message) {
        return IntentResult.clarificationNeeded(message);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    public static final class Attempt {

        private final IdempotencyService service;
        private final ObjectMapper objectMapper;
        private final WorkspaceContext context;
        private final String channel;
        private final String key;
        private final IntentResult replay;
        private boolean executionStarted;
        private boolean finished;

        private Attempt(IdempotencyService service, ObjectMapper objectMapper,
                        WorkspaceContext context,
                        String channel, String key, IntentResult replay) {
            this.service = service;
            this.objectMapper = objectMapper;
            this.context = context;
            this.channel = channel;
            this.key = key;
            this.replay = replay;
        }

        public static Attempt disabled() {
            return new Attempt(null, null, null, null, null, null);
        }

        static Attempt reserved(IdempotencyService service, ObjectMapper objectMapper,
                                WorkspaceContext context,
                                String channel, String key) {
            return new Attempt(service, objectMapper, context, channel, key, null);
        }

        static Attempt replay(IntentResult result) {
            return new Attempt(null, null, null, null, null,
                    Objects.requireNonNull(result, "result"));
        }

        public Optional<IntentResult> replay() {
            return Optional.ofNullable(replay);
        }

        public void beforeMutation() {
            if (service == null || replay != null || executionStarted) {
                return;
            }
            service.markExecutionStarted(context.workspaceId(), context.actorId(), channel, key);
            executionStarted = true;
        }

        public void complete(IntentResult result) {
            if (service == null || replay != null || finished) {
                return;
            }
            finished = true;
            try {
                if (executionStarted) {
                    service.complete(context.workspaceId(), context.actorId(), channel, key,
                            result.action().name(), encode(objectMapper, result));
                } else {
                    service.failBeforeExecution(context.workspaceId(), context.actorId(), channel, key,
                            "READ_ONLY_COMPLETED");
                }
            } catch (RuntimeException persistenceFailure) {
                log.warn("Conversation replay finalization failed [cause={}]",
                        persistenceFailure.getClass().getSimpleName());
            }
        }

        public void failBeforeExecution() {
            if (service == null || replay != null || executionStarted || finished) {
                return;
            }
            finished = true;
            try {
                service.failBeforeExecution(context.workspaceId(), context.actorId(), channel, key,
                        "PRE_EXECUTION_FAILED");
            } catch (RuntimeException persistenceFailure) {
                log.warn("Conversation replay pre-execution failure could not be recorded [cause={}]",
                        persistenceFailure.getClass().getSimpleName());
            }
        }

        private static String encode(ObjectMapper objectMapper, IntentResult result) {
            try {
                FocusTransitionNotice notice = result.focusNotice();
                StoredNotice storedNotice = notice == null ? null : new StoredNotice(
                        notice.type(), notice.previousSafeLabel(), notice.currentSafeLabel(),
                        notice.activitySafeLabel());
                var question = result.nextQuestion();
                StoredQuestion storedQuestion = question == null ? null
                        : new StoredQuestion(question.code(), question.prompt());
                return objectMapper.writeValueAsString(
                        new StoredReply(2, result.message(), storedNotice, storedQuestion));
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Conversation replay response could not be encoded",
                        invalid);
            }
        }
    }

    private record StoredReply(
            int version, String message, StoredNotice notice, StoredQuestion question) {}

    private record StoredQuestion(String code, String prompt) {

        PublicConversationReply.NextQuestion toQuestion() {
            return new PublicConversationReply.NextQuestion(code, prompt);
        }
    }

    private record StoredNotice(FocusTransitionType type, String previousSafeLabel,
                                String currentSafeLabel, String activitySafeLabel) {

        FocusTransitionNotice toNotice() {
            return FocusTransitionNotice.forTransition(
                    type, previousSafeLabel, currentSafeLabel, activitySafeLabel);
        }
    }
}
