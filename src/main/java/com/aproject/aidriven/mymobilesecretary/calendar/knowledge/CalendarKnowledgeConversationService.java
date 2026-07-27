package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationInboundIdempotency;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationSafeFailureException;
import com.aproject.aidriven.mymobilesecretary.conversation.application.TrustedConversationReferenceContext;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeEvidence;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeQuery;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeSourceType;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.PersonalKnowledgeRetriever;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic Java orchestration for Calendar-bound personal knowledge. */
@Service
@Transactional
public class CalendarKnowledgeConversationService {

    private static final int READ_LIMIT = 5;

    private final CalendarKnowledgeIntentTargetResolver targets;
    private final CalendarKnowledgeReadService reads;
    private final CalendarKnowledgeBindingService bindings;
    private final PersonalKnowledgeRetriever knowledge;
    private final KnowledgeMaterializationService materializations;

    public CalendarKnowledgeConversationService(
            CalendarKnowledgeIntentTargetResolver targets,
            CalendarKnowledgeReadService reads,
            CalendarKnowledgeBindingService bindings,
            PersonalKnowledgeRetriever knowledge,
            KnowledgeMaterializationService materializations) {
        this.targets = targets;
        this.reads = reads;
        this.bindings = bindings;
        this.knowledge = knowledge;
        this.materializations = materializations;
    }

    @Transactional(readOnly = true)
    public IntentResult ask(IntentCommand command) {
        IntentOptions options = command.safeOptions();
        if (tooLong(command.title())) {
            return IntentResult.clarificationNeeded(
                    "知識查詢詞太長，請縮短後再試。這次沒有變更資料。");
        }
        var target = targets.resolve(
                options.referenceTitle(),
                defaultKind(options.category()),
                options.alias());
        if (target.isEmpty()) {
            return targetClarification();
        }
        List<CalendarKnowledgeEvidenceView> evidence =
                reads.retrieve(target.get(), boundedQuery(command.title()), READ_LIMIT);
        if (evidence.isEmpty()) {
            return IntentResult.message(
                    IntentResult.Action.CALENDAR_KNOWLEDGE_LISTED,
                    "這個行程圖目標目前沒有符合的已綁知識。");
        }
        StringBuilder reply = new StringBuilder("找到已綁到這個行程圖目標的知識：");
        for (int index = 0; index < evidence.size(); index++) {
            CalendarKnowledgeEvidenceView item = evidence.get(index);
            reply.append("\n")
                    .append(index + 1)
                    .append(".「")
                    .append(item.title())
                    .append("」：")
                    .append(boundedContent(item.content()));
        }
        return IntentResult.message(
                IntentResult.Action.CALENDAR_KNOWLEDGE_LISTED, reply.toString());
    }

    public IntentResult bind(IntentCommand command) {
        IntentOptions options = command.safeOptions();
        var target = targets.resolve(
                options.referenceTitle(),
                defaultKind(options.category()),
                options.alias());
        if (target.isEmpty()) {
            return targetClarification();
        }
        KnowledgeSourceType sourceType = sourceType(options.referenceKind());
        if (sourceType == null
                || command.title() == null
                || command.title().isBlank()
                || tooLong(command.title())) {
            return IntentResult.clarificationNeeded(
                    "請說明要綁定的是知識事實或註記，並提供知識名稱。這次沒有變更資料。");
        }
        List<KnowledgeEvidence> candidates = retrieve(command.title(), sourceType);
        KnowledgeEvidence selected = unique(command.title(), candidates);
        if (selected == null) {
            return IntentResult.clarificationNeeded(
                    "找不到唯一的本人知識；請補充更完整的知識名稱。這次沒有變更資料。");
        }
        long sourceId;
        try {
            sourceId = Long.parseLong(selected.sourceId());
            if (sourceId <= 0) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException invalid) {
            return IntentResult.clarificationNeeded(
                    "這筆知識目前無法安全綁定。這次沒有變更資料。");
        }
        String inbound = ConversationInboundIdempotency.fromRequestId(
                RequestCorrelationContext.currentId());
        String requestKey = "calendar-knowledge-intent-bind:"
                + hash(inbound + "|" + sourceType + "|" + sourceId + "|" + target.get());
        if (sourceType == KnowledgeSourceType.USER_KNOWLEDGE_FACT) {
            bindings.bindFact(requestKey, sourceId, target.get());
        } else {
            bindings.bindAnnotation(requestKey, sourceId, target.get());
        }
        return IntentResult.message(
                IntentResult.Action.CALENDAR_KNOWLEDGE_BOUND,
                "已把「%s」綁到指定行程圖目標。這不會自動分享原文，也不會改動時間、待辦或提醒。"
                        .formatted(selected.title()));
    }

    public IntentResult materialize(IntentCommand command) {
        IntentOptions options = command.safeOptions();
        String operation = normalize(options.condition()).toUpperCase(Locale.ROOT);
        context();
        KnowledgeMaterializationConversationScope conversationScope =
                KnowledgeMaterializationConversationScope.current();
        String channel = conversationScope.channel();
        String scope = conversationScope.scopeKey();
        java.util.UUID trustedProposalId =
                TrustedConversationReferenceContext.currentMaterializationProposalId();
        String inbound = ConversationInboundIdempotency.fromRequestId(
                RequestCorrelationContext.currentId());
        String requestKey = "calendar-knowledge-materialize:"
                + hash(inbound + "|" + operation + "|" + command);

        try {
            if ("CONFIRM".equals(operation)) {
                materializations.confirmPending(
                        requestKey, channel, scope, trustedProposalId);
                return IntentResult.message(
                        IntentResult.Action.CALENDAR_KNOWLEDGE_MATERIALIZED,
                        "已依你剛才的明確確認建立項目；重送同一請求不會重複建立。");
            }
            if ("CANCEL".equals(operation)) {
                materializations.cancelPending(
                        requestKey, channel, scope, trustedProposalId);
                return IntentResult.message(
                        IntentResult.Action
                                .CALENDAR_KNOWLEDGE_MATERIALIZATION_CANCELED,
                        "已取消這次知識轉換提案，沒有建立待辦或行程圖節點。");
            }
            if (!"PROPOSE".equals(operation)) {
                return IntentResult.clarificationNeeded(
                        "請明確說要提出、確認或取消知識轉換。這次沒有變更資料。");
            }

            var target = targets.resolve(
                    options.referenceTitle(),
                    defaultKind(options.category()),
                    options.alias());
            if (target.isEmpty()) {
                return targetClarification();
            }
            KnowledgeSourceType sourceType = sourceType(options.triggerType());
            KnowledgeEvidence selected = uniqueSource(command.title(), sourceType);
            if (selected == null) {
                return IntentResult.clarificationNeeded(
                        "找不到唯一的本人知識；請補充知識種類與完整名稱。這次沒有變更資料。");
            }
            long sourceId = positiveSourceId(selected.sourceId());
            CalendarKnowledgeBindingView binding =
                    sourceType == KnowledgeSourceType.USER_KNOWLEDGE_FACT
                            ? bindings.getFactBinding(sourceId, target.get())
                            : bindings.getAnnotationBinding(sourceId, target.get());
            KnowledgeMaterializationCommand typedCommand =
                    typedCommand(command, options, binding);
            if (typedCommand == null) {
                return IntentResult.clarificationNeeded(
                        "目前只能把已綁知識提出為待辦或行程圖節點，並需提供新標題與必要時間。這次沒有變更資料。");
            }
            materializations.prepare(
                    requestKey,
                    new KnowledgeMaterializationSource(
                            binding.sourceKind(),
                            binding.id(),
                            binding.sourceUpdatedAt(),
                            binding.revision(),
                            channel,
                            scope),
                    typedCommand);
            return IntentResult.message(
                    IntentResult.Action
                            .CALENDAR_KNOWLEDGE_MATERIALIZATION_PROPOSED,
                    "已準備知識轉換提案，但尚未建立任何項目。請明確回覆確認或取消。");
        } catch (BusinessException conflict) {
            if ("KNOWLEDGE_MATERIALIZATION_PENDING_NOT_UNIQUE"
                    .equals(conflict.getCode())) {
                throw new ConversationSafeFailureException(
                        IntentResult.clarificationNeeded(
                                "目前找不到唯一待確認的知識轉換提案；請引用指定提案後再確認或取消。這次沒有變更資料。"));
            }
            throw new ConversationSafeFailureException(
                    IntentResult.clarificationNeeded(
                            "這筆知識轉換提案已變更、過期或目前無法安全處理；請重新提出。這次沒有變更資料。"));
        } catch (IllegalArgumentException | NotFoundException invalid) {
            throw new ConversationSafeFailureException(
                    IntentResult.clarificationNeeded(
                            "無法安全解析唯一的已綁知識或轉換內容；請補充完整名稱與時間。這次沒有變更資料。"));
        }
    }

    private KnowledgeEvidence uniqueSource(
            String title, KnowledgeSourceType sourceType) {
        if (sourceType == null
                || title == null
                || title.isBlank()
                || tooLong(title)) {
            return null;
        }
        return unique(title, retrieve(title, sourceType));
    }

    private static long positiveSourceId(String value) {
        long sourceId = Long.parseLong(value);
        if (sourceId <= 0) {
            throw new IllegalArgumentException("knowledge source id must be positive");
        }
        return sourceId;
    }

    private static KnowledgeMaterializationCommand typedCommand(
            IntentCommand command,
            IntentOptions options,
            CalendarKnowledgeBindingView binding) {
        String targetKind = normalize(options.referenceKind())
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        if ("TASK".equals(targetKind)) {
            return new KnowledgeMaterializationCommand.CreateTask(
                    options.newTitle(),
                    parseInstant(command.dueAt(), false),
                    priority(command.priority()));
        }
        if ("CALENDAR_NODE".equals(targetKind)) {
            Instant time = parseInstant(command.startAt(), true);
            String nodeKey = "knowledge-"
                    + hash(binding.id()
                                    + "|"
                                    + normalize(options.newTitle())
                                    + "|"
                                    + time)
                            .substring(0, 24);
            return new KnowledgeMaterializationCommand.CreateCalendarNode(
                    nodeKey, options.newTitle(), time);
        }
        return null;
    }

    private static Instant parseInstant(String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("time is required");
            }
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (java.time.format.DateTimeParseException ignored) {
            return OffsetDateTime.parse(value.strip()).toInstant();
        }
    }

    private static TaskPriority priority(String value) {
        if (value == null || value.isBlank()) {
            return TaskPriority.NORMAL;
        }
        return TaskPriority.valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    private List<KnowledgeEvidence> retrieve(
            String queryText, KnowledgeSourceType sourceType) {
        WorkspaceContext context = context();
        return knowledge.retrieve(new KnowledgeQuery(
                context.workspaceId(),
                context.actorId(),
                boundedQuery(queryText),
                Set.of(),
                Set.of(sourceType),
                KnowledgeQuery.MAX_LIMIT,
                false,
                null,
                null,
                Map.of()));
    }

    private static KnowledgeEvidence unique(
            String requestedTitle, List<KnowledgeEvidence> candidates) {
        String normalized = normalize(requestedTitle);
        List<KnowledgeEvidence> exact = candidates.stream()
                .filter(candidate -> normalize(candidate.title()).equals(normalized))
                .toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        return exact.size() == 1 ? exact.getFirst() : null;
    }

    private static KnowledgeSourceType sourceType(String value) {
        String normalized = normalize(value).replace(' ', '_');
        return switch (normalized) {
            case "fact", "knowledge_fact", "user_knowledge_fact" ->
                KnowledgeSourceType.USER_KNOWLEDGE_FACT;
            case "annotation", "knowledge_annotation", "object_annotation" ->
                KnowledgeSourceType.OBJECT_ANNOTATION;
            default -> null;
        };
    }

    private static String defaultKind(String value) {
        return value == null || value.isBlank() ? "PLAN" : value.strip();
    }

    private static String boundedQuery(String value) {
        String query = value == null ? "" : value.strip();
        if (query.length() > KnowledgeQuery.MAX_QUERY_TEXT_LENGTH) {
            throw new IllegalArgumentException("knowledge query text is too long");
        }
        return query;
    }

    private static boolean tooLong(String value) {
        return value != null
                && value.strip().length() > KnowledgeQuery.MAX_QUERY_TEXT_LENGTH;
    }

    private static String boundedContent(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.strip();
        return cleaned.length() <= 300 ? cleaned : cleaned.substring(0, 300) + "…";
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IntentResult targetClarification() {
        return IntentResult.clarificationNeeded(
                "找不到唯一的本人行程圖目標；請補充完整行程名稱與活動或節點名稱。這次沒有變更資料。");
    }

    private static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Calendar knowledge conversation requires tenant scope");
        }
        return context;
    }
}
