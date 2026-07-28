package com.aproject.aidriven.mymobilesecretary.calendar.attachment;

import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeIntentTargetResolver;
import com.aproject.aidriven.mymobilesecretary.calendar.knowledge.CalendarKnowledgeTarget;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationInboundIdempotency;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationSafeFailureException;
import com.aproject.aidriven.mymobilesecretary.conversation.application.TrustedConversationReferenceContext;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Binds a trusted quoted media item and previews separate share authorizations. */
@Service
@Transactional
public class CalendarAttachmentConversationService {

    private final CalendarKnowledgeIntentTargetResolver targets;
    private final CalendarAttachmentBindingService bindings;

    public CalendarAttachmentConversationService(
            CalendarKnowledgeIntentTargetResolver targets,
            CalendarAttachmentBindingService bindings) {
        this.targets = targets;
        this.bindings = bindings;
    }

    public IntentResult bindAndPreviewShare(IntentCommand command) {
        IntentOptions options = command.safeOptions();
        if (!"BIND_AND_PREVIEW_SHARE"
                .equals(normalize(options.condition()))) {
            return clarification(
                    "請明確說要把引用附件綁到行程，並預覽分享。這次沒有變更資料。");
        }
        Long mediaId = TrustedConversationReferenceContext.currentMediaId();
        if (mediaId == null) {
            return clarification(
                    "請直接引用本人剛上傳的檔案，再指定唯一行程圖目標。這次沒有變更資料。");
        }
        var resolved = targets.resolve(
                options.referenceTitle(),
                options.category() == null ? "PLAN" : options.category(),
                options.alias());
        if (resolved.isEmpty()) {
            return clarification(
                    "找不到唯一的本人行程圖目標；請補充完整行程名稱與活動或節點名稱。這次沒有變更資料。");
        }
        CalendarAttachmentTarget target = attachmentTarget(resolved.get());
        String displayName = command.title() == null || command.title().isBlank()
                ? "行程附件"
                : command.title().strip();
        String inbound = ConversationInboundIdempotency.fromRequestId(
                RequestCorrelationContext.currentId());
        try {
            bindings.bind(
                    "calendar-attachment-intent:"
                            + hash(inbound + "|" + mediaId + "|" + target),
                    mediaId,
                    target,
                    displayName,
                    0);
        } catch (BusinessException | NotFoundException invalid) {
            throw new ConversationSafeFailureException(clarification(
                    "找不到可綁定的本人附件，或附件狀態已變更。請重新引用本人剛上傳的檔案。這次沒有變更資料。"));
        }
        return IntentResult.message(
                IntentResult.Action.CALENDAR_ATTACHMENT_BOUND_SHARE_PREVIEWED,
                """
                已把引用的檔案綁到指定行程圖目標。
                分享預覽分成兩項：
                1. 行程核心分享：尚未建立，需先確認唯一收件人。
                2. 附件內容授權：尚未建立，必須逐一對每位收件人明確授權。
                行程分享不會自動讓任何人下載附件。
                """
                        .strip());
    }

    public IntentResult manage(IntentCommand command) {
        IntentOptions options = command.safeOptions();
        String operation = normalize(options.condition());
        var resolved = targets.resolve(
                options.referenceTitle(),
                options.category() == null ? "PLAN" : options.category(),
                options.alias());
        if (resolved.isEmpty()
                || command.title() == null
                || command.title().isBlank()) {
            return clarification(
                    "請補充唯一行程圖目標與附件顯示名稱。這次沒有變更資料。");
        }
        CalendarAttachmentTarget target = attachmentTarget(resolved.get());
        var active =
                bindings.findUniqueActiveForConversation(
                        target, command.title());
        if (active.isEmpty()) {
            return clarification(
                    "找不到唯一且可操作的本人行程附件，或附件狀態已變更。這次沒有變更資料。");
        }
        try {
            CalendarAttachmentBindingView binding = active.orElseThrow();
            if ("DELETE_SOURCE".equals(operation)) {
                return IntentResult.message(
                        IntentResult.Action
                                .CALENDAR_ATTACHMENT_DELETE_CONFIRMATION_REQUIRED,
                        "這會永久刪除原始檔案，並使所有相關附件授權失效；目前尚未刪除。請到檔案管理明確確認永久刪除。");
            }
            if ("UNLINK".equals(operation)) {
                bindings.unlink(binding.id(), binding.revision());
                return IntentResult.message(
                        IntentResult.Action.CALENDAR_ATTACHMENT_UNLINKED,
                        "已從指定行程圖目標移除附件連結；原始檔案仍保留在你的私密檔案區。");
            }
            if ("REPLACE".equals(operation)) {
                Long replacementMediaId =
                        TrustedConversationReferenceContext.currentMediaId();
                if (replacementMediaId == null) {
                    return clarification(
                            "請直接引用本人剛上傳的新版檔案，再說要替換哪個行程附件。這次沒有變更資料。");
                }
                String inbound = ConversationInboundIdempotency.fromRequestId(
                        RequestCorrelationContext.currentId());
                bindings.replace(
                        "calendar-attachment-replace:"
                                + hash(inbound
                                        + "|"
                                        + binding.id()
                                        + "|"
                                        + replacementMediaId),
                        binding.id(),
                        replacementMediaId,
                        binding.revision());
                return IntentResult.message(
                        IntentResult.Action.CALENDAR_ATTACHMENT_REPLACED,
                        "已換成引用的新版附件。舊的附件內容授權已失效；若要讓收件人查看新版，需重新逐一授權。");
            }
            return clarification(
                    "請明確說要從行程移除附件、換成引用的新版，或預覽永久刪除原檔。這次沒有變更資料。");
        } catch (BusinessException | NotFoundException invalid) {
            throw new ConversationSafeFailureException(clarification(
                    "找不到唯一且可操作的本人行程附件，或附件狀態已變更。這次沒有變更資料。"));
        }
    }

    private static CalendarAttachmentTarget attachmentTarget(
            CalendarKnowledgeTarget target) {
        return switch (target.kind()) {
            case PLAN -> CalendarAttachmentTarget.plan(target.planId());
            case ACTIVITY ->
                CalendarAttachmentTarget.activity(
                        target.planId(), target.activityId());
            case NODE ->
                CalendarAttachmentTarget.node(target.planId(), target.nodeId());
        };
    }

    private static IntentResult clarification(String message) {
        return IntentResult.clarificationNeeded(message);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.strip()
                        .replace(' ', '_')
                        .toUpperCase(Locale.ROOT);
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
}
