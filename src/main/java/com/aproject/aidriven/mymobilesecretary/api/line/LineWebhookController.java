package com.aproject.aidriven.mymobilesecretary.api.line;

import com.aproject.aidriven.mymobilesecretary.account.application.ExternalIdentityService;
import com.aproject.aidriven.mymobilesecretary.account.application.ExternalIdentityService.Resolution;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditDraft;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditEvent;
import com.aproject.aidriven.mymobilesecretary.account.audit.SecurityAuditService;
import com.aproject.aidriven.mymobilesecretary.account.domain.WorkspaceRole;
import com.aproject.aidriven.mymobilesecretary.account.security.idempotency.IdempotencyService;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineContentClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogService;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessagingClient;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineProperties;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineSignatureVerifier;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineWebhookPayload;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptService;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import com.aproject.aidriven.mymobilesecretary.shared.observability.SensitiveValueFingerprint;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signed LINE entry point. Every business event resolves an internal actor, opens an explicit
 * workspace scope and reserves a webhook id before it can produce a side effect.
 */
@RestController
@RequestMapping("/api/line")
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

    private final LineSignatureVerifier signatureVerifier;
    private final LineMessagingClient messagingClient;
    private final LineContentClient contentClient;
    private final LineProperties properties;
    private final IntentService intentService;
    private final ReceiptService receiptService;
    private final LineMessageLogService messageLogService;
    private final ExternalIdentityService identityService;
    private final IdempotencyService idempotencyService;
    private final SecurityAuditService securityAuditService;
    private final ObjectMapper objectMapper;

    public LineWebhookController(LineSignatureVerifier signatureVerifier,
                                 LineMessagingClient messagingClient,
                                 LineContentClient contentClient,
                                 LineProperties properties,
                                 IntentService intentService,
                                 ReceiptService receiptService,
                                 LineMessageLogService messageLogService,
                                 ExternalIdentityService identityService,
                                 IdempotencyService idempotencyService,
                                 SecurityAuditService securityAuditService,
                                 ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.messagingClient = messagingClient;
        this.contentClient = contentClient;
        this.properties = properties;
        this.intentService = intentService;
        this.receiptService = receiptService;
        this.messageLogService = messageLogService;
        this.identityService = identityService;
        this.idempotencyService = idempotencyService;
        this.securityAuditService = securityAuditService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader(value = "X-Line-Signature", required = false) String signature,
            @RequestBody byte[] rawBody) {
        if (!properties.usable()) {
            return ResponseEntity.ok().build();
        }
        if (!signatureVerifier.verify(rawBody, signature, properties.channelSecret())) {
            log.warn("LINE webhook signature verification failed");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        LineWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, LineWebhookPayload.class);
        } catch (Exception invalidPayload) {
            log.warn("LINE webhook payload unparsable");
            return ResponseEntity.ok().build();
        }

        if (payload.events() == null) {
            return ResponseEntity.ok().build();
        }
        for (LineWebhookPayload.Event event : payload.events()) {
            processEvent(event);
        }
        return ResponseEntity.ok().build();
    }

    private void processEvent(LineWebhookPayload.Event event) {
        if (event == null || (!event.isTextMessage() && !event.isImageMessage())) {
            return;
        }

        Resolution resolution;
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(
                WorkspaceContext.authentication())) {
            resolution = resolveIdentity(event.sourceUserId());
            if (!resolution.isResolved()) {
                recordDeniedIdentity(event.sourceUserId(), resolution);
                return;
            }
        }

        var identity = resolution.identity();
        WorkspaceContext context = new WorkspaceContext(
                identity.actorUserId(), identity.workspaceId(), WorkspaceChannel.LINE);
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            String eventKey = event.idempotencyKey();
            UUID eventRequestId = eventKey == null
                    ? UUID.randomUUID()
                    : UUID.nameUUIDFromBytes(
                            ("line:" + eventKey).getBytes(StandardCharsets.UTF_8));
            RequestCorrelationContext.run(eventRequestId, () -> {
                if (!identity.role().grants(WorkspaceRole.MEMBER)) {
                    denyViewerConversation(event, identity.actorUserId(), identity.workspaceId());
                } else {
                    processAuthorizedEvent(event, identity.actorUserId(), identity.workspaceId());
                }
                return null;
            });
        }
    }

    private Resolution resolveIdentity(String sourceUserId) {
        try {
            return identityService.resolveLine(sourceUserId, properties.ownerUserId());
        } catch (IllegalArgumentException missingIdentity) {
            return Resolution.failed(ExternalIdentityService.ResolutionStatus.NOT_LINKED);
        }
    }

    private void processAuthorizedEvent(LineWebhookPayload.Event event,
                                        UUID actorUserId,
                                        UUID workspaceId) {
        String eventKey = event.idempotencyKey();
        IdempotencyService.BeginResult reservation = eventKey == null
                ? null
                : idempotencyService.begin(workspaceId, actorUserId, "LINE", eventKey,
                        requestFingerprintMaterial(event));

        if (reservation != null && reservation.state() != IdempotencyService.State.NEW) {
            handleExistingDelivery(event, actorUserId, workspaceId, reservation);
            return;
        }

        ExecutionBoundary executionBoundary = new ExecutionBoundary(
                workspaceId, actorUserId, eventKey);
        PreparedReply reply;
        try {
            reply = event.isTextMessage()
                    ? prepareTextReply(event, executionBoundary)
                    : prepareImageReply(event, executionBoundary);
        } catch (Exception failure) {
            if (eventKey != null) {
                if (executionBoundary.started()) {
                    recordUnknownResultSafely(workspaceId, actorUserId, eventKey);
                } else {
                    failReservationSafely(workspaceId, actorUserId, eventKey);
                }
            }
            String failureCode = executionBoundary.started()
                    ? "PROCESSING_RESULT_UNKNOWN" : "PRE_EXECUTION_FAILED";
            securityAuditService.recordSafely(new SecurityAuditDraft(
                    workspaceId, actorUserId, "LINE_EVENT_PROCESSING", "WEBHOOK_EVENT",
                    eventKey == null ? null : SensitiveValueFingerprint.of(eventKey),
                    SecurityAuditEvent.Outcome.FAILED, failureCode, "LINE",
                    RequestCorrelationContext.currentId()));
            log.warn("LINE event processing failed [event={}, cause={}]",
                    SensitiveValueFingerprint.of(eventKey), failure.getClass().getSimpleName());
            messagingClient.reply(event.replyToken(),
                    "⚠️ 這次訊息暫時無法處理。\n\n🔄 請稍後再試一次。");
            return;
        }

        // A finalization outage leaves UNKNOWN (at-most-once), never FAILED/retryable.
        if (eventKey != null) {
            completeReservationSafely(workspaceId, actorUserId, eventKey, reply);
        }
        messagingClient.reply(event.replyToken(), reply.message());
        messageLogService.recordSafely(LineMessageLog.Direction.OUT, "TEXT", reply.message());
    }

    private PreparedReply prepareTextReply(LineWebhookPayload.Event event,
                                           ExecutionBoundary executionBoundary) {
        messageLogService.recordSafely(LineMessageLog.Direction.IN, "TEXT", event.message().text());
        IntentResult result = intentService.handle(
                event.message().text(), "LINE", executionBoundary::beforeMutation);
        return new PreparedReply(result.action().name(), result.message());
    }

    private PreparedReply prepareImageReply(LineWebhookPayload.Event event,
                                            ExecutionBoundary executionBoundary) throws Exception {
        messageLogService.recordSafely(LineMessageLog.Direction.IN, "IMAGE", "[收據圖片]");
        LineContentClient.MessageContent content = contentClient.fetchContent(event.message().id());
        executionBoundary.beforeMutation();
        ReceiptService.ReceiptResult result = receiptService.handleImage(
                content.bytes(), content.mimeType());
        return new PreparedReply("RECEIPT_IMPORTED", result.message());
    }

    private void handleExistingDelivery(LineWebhookPayload.Event event,
                                        UUID actorUserId,
                                        UUID workspaceId,
                                        IdempotencyService.BeginResult reservation) {
        switch (reservation.state()) {
            case REPLAY_AVAILABLE -> messagingClient.reply(
                    event.replyToken(), reservation.responseBody());
            case CONFLICT -> securityAuditService.recordSafely(new SecurityAuditDraft(
                    workspaceId, actorUserId, "LINE_IDEMPOTENCY_CONFLICT", "WEBHOOK_EVENT",
                    SensitiveValueFingerprint.of(event.idempotencyKey()),
                    SecurityAuditEvent.Outcome.DENIED, "KEY_REUSED_WITH_DIFFERENT_REQUEST",
                    "LINE", RequestCorrelationContext.currentId()));
            case IN_PROGRESS, RESULT_UNKNOWN, COMPLETED_NO_REPLAY, PREVIOUS_FAILED -> {
                // A previous delivery owns or already completed the operation. Never execute twice.
            }
            case NEW -> throw new IllegalStateException("NEW delivery must be processed by caller");
        }
    }

    private void recordDeniedIdentity(String sourceUserId, Resolution resolution) {
        String fingerprint = SensitiveValueFingerprint.of(sourceUserId);
        securityAuditService.recordSafely(new SecurityAuditDraft(
                null, null, "LINE_IDENTITY_RESOLUTION", "LINE_SUBJECT", fingerprint,
                SecurityAuditEvent.Outcome.DENIED, resolution.status().name(), "LINE",
                RequestCorrelationContext.currentId()));
        log.warn("LINE event denied [reason={}, sender={}]", resolution.status(), fingerprint);
    }

    private void denyViewerConversation(LineWebhookPayload.Event event,
                                        UUID actorUserId,
                                        UUID workspaceId) {
        securityAuditService.recordSafely(new SecurityAuditDraft(
                workspaceId, actorUserId, "LINE_CONVERSATION_ROLE", "WORKSPACE",
                workspaceId.toString(), SecurityAuditEvent.Outcome.DENIED,
                "WORKSPACE_ROLE_REQUIRED", "LINE", RequestCorrelationContext.currentId()));
        messagingClient.reply(event.replyToken(),
                "🔒 這個工作區角色目前只能檢視資料，尚不能透過對話執行操作。");
    }

    private void failReservationSafely(UUID workspaceId, UUID actorUserId, String eventKey) {
        try {
            idempotencyService.failBeforeExecution(workspaceId, actorUserId, "LINE", eventKey,
                    "PROCESSING_FAILED");
        } catch (RuntimeException persistenceFailure) {
            log.warn("LINE idempotency failure could not be recorded [event={}]",
                    SensitiveValueFingerprint.of(eventKey));
        }
    }

    private void recordUnknownResultSafely(UUID workspaceId, UUID actorUserId, String eventKey) {
        try {
            idempotencyService.recordUnknownResult(workspaceId, actorUserId, "LINE", eventKey,
                    "PROCESSING_RESULT_UNKNOWN");
        } catch (RuntimeException persistenceFailure) {
            // markExecutionStarted already persisted UNKNOWN; diagnostics are best effort only.
            log.warn("LINE unknown idempotency result could not be annotated [event={}]",
                    SensitiveValueFingerprint.of(eventKey));
        }
    }

    private void completeReservationSafely(UUID workspaceId, UUID actorUserId,
                                           String eventKey, PreparedReply reply) {
        try {
            idempotencyService.complete(workspaceId, actorUserId, "LINE", eventKey,
                    reply.action(), reply.message());
        } catch (RuntimeException persistenceFailure) {
            securityAuditService.recordSafely(new SecurityAuditDraft(
                    workspaceId, actorUserId, "LINE_IDEMPOTENCY_FINALIZATION", "WEBHOOK_EVENT",
                    SensitiveValueFingerprint.of(eventKey), SecurityAuditEvent.Outcome.FAILED,
                    "COMPLETION_WRITE_FAILED", "LINE", RequestCorrelationContext.currentId()));
            log.warn("LINE idempotency completion could not be recorded [event={}]",
                    SensitiveValueFingerprint.of(eventKey));
        }
    }

    private static String requestFingerprintMaterial(LineWebhookPayload.Event event) {
        LineWebhookPayload.Message message = event.message();
        return String.join("|",
                nullToEmpty(event.type()),
                String.valueOf(event.timestamp()),
                message == null ? "" : nullToEmpty(message.id()),
                message == null ? "" : nullToEmpty(message.type()),
                message == null ? "" : nullToEmpty(message.text()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private final class ExecutionBoundary {

        private final UUID workspaceId;
        private final UUID actorUserId;
        private final String eventKey;
        private boolean started;

        private ExecutionBoundary(UUID workspaceId, UUID actorUserId, String eventKey) {
            this.workspaceId = workspaceId;
            this.actorUserId = actorUserId;
            this.eventKey = eventKey;
        }

        private void beforeMutation() {
            if (started) {
                return;
            }
            if (eventKey != null) {
                idempotencyService.markExecutionStarted(
                        workspaceId, actorUserId, "LINE", eventKey);
            }
            started = true;
        }

        private boolean started() {
            return started;
        }
    }

    private record PreparedReply(String action, String message) {
    }
}
