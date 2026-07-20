package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 編號知識紀錄的兩階段安全刪除；實際採可恢復封存。 */
@Service
@Transactional
public class KnowledgeRecordDeletionService {
    private static final Pattern ORDINAL = Pattern.compile(
            "(?:刪除|刪掉|移除)(?:第)?([一二三四五六七八九十]|\\d{1,2})(?:筆|個)?");

    private final ObjectAnnotationRepository repository;
    private final ConversationContextService context;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public KnowledgeRecordDeletionService(ObjectAnnotationRepository repository,
                                          ConversationContextService context,
                                          ApplicationEventPublisher events,
                                          Clock clock) {
        this.repository = repository;
        this.context = context;
        this.events = events;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (text == null || text.isBlank()) return Optional.empty();
        String normalized = text.strip();
        if (isCancel(normalized) && context.pendingObjectAnnotationDeleteId() != null) {
            context.clearObjectAnnotationDelete();
            return Optional.of(IntentResult.message(IntentResult.Action.TAGGED_RECORDS_INFO,
                    "已取消刪除，知識紀錄沒有異動。"));
        }
        if (isConfirm(normalized) && context.pendingObjectAnnotationDeleteId() != null) {
            Long id = context.pendingObjectAnnotationDeleteId();
            ObjectAnnotation annotation = repository.findById(id).orElse(null);
            if (annotation == null || annotation.isArchived()) {
                context.clearObjectAnnotationDelete();
                return Optional.of(IntentResult.clarificationNeeded("這筆知識紀錄已不存在或已刪除，沒有再次異動。"));
            }
            beforeMutation.run();
            Instant now = Instant.now(clock);
            annotation.archive(now);
            context.clearObjectAnnotationDelete();
            events.publishEvent(new ObjectAnnotationArchivedEvent(id, annotation.getSubject(), now));
            return Optional.of(IntentResult.message(IntentResult.Action.TAGGED_RECORDS_INFO,
                    "已刪除知識紀錄「%s」。這是可恢復封存，不會再出現在一般查詢。"
                            .formatted(annotation.getSubject())));
        }

        Matcher matcher = ORDINAL.matcher(normalized);
        if (!matcher.find()) return Optional.empty();
        int ordinal = ordinal(matcher.group(1));
        Long id = context.objectAnnotationIdAt(ordinal);
        ObjectAnnotation annotation = id == null ? null : repository.findById(id).orElse(null);
        if (annotation == null || annotation.isArchived()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "找不到上一份知識清單的第 %d 筆；請先重新查詢，系統會用 1、2、3… 編號。"
                            .formatted(ordinal)));
        }
        context.prepareObjectAnnotationDelete(id);
        return Optional.of(IntentResult.clarificationNeeded(
                "⚠️ 準備刪除第 %d 筆知識紀錄「%s」｜%s\n請回覆「確認刪除」才會執行，或回覆「取消刪除」。"
                        .formatted(ordinal, annotation.getSubject(), annotation.getDetail())));
    }

    private static boolean isConfirm(String text) {
        return text.matches(".*(?:確認刪除|確定刪除|確認移除).*?");
    }

    private static boolean isCancel(String text) {
        return text.matches(".*(?:取消刪除|不要刪|保留).*?");
    }

    private static int ordinal(String value) {
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        if ("十".equals(value)) return 10;
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : chineseDigit(value.charAt(0));
            int ones = ten == value.length() - 1 ? 0 : chineseDigit(value.charAt(ten + 1));
            return tens * 10 + ones;
        }
        return chineseDigit(value.charAt(0));
    }

    private static int chineseDigit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }
}
