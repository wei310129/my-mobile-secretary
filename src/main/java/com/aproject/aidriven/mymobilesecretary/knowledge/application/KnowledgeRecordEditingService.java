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

/** Updates a specifically numbered durable knowledge record without creating a duplicate. */
@Service
@Transactional
public class KnowledgeRecordEditingService {
    private static final String NUMBER = "([一二三四五六七八九十]|\\d{1,2})";
    private static final Pattern TITLE = Pattern.compile(
            "^(?:把)?(?:第)?" + NUMBER
                    + "筆知識紀錄(?:的)?標題(?:修改|更新|改成)(?:為|成)?[：:\\s]*(.+)$");
    private static final Pattern DETAIL = Pattern.compile(
            "^(?:把)?(?:第)?" + NUMBER
                    + "筆知識紀錄(?:的)?(?:內容)?(?:修改|編修|更新|改成)(?:為|成)?[：:\\s]*(.+)$");

    private final ObjectAnnotationRepository repository;
    private final ConversationContextService context;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public KnowledgeRecordEditingService(ObjectAnnotationRepository repository,
                                         ConversationContextService context,
                                         ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.context = context;
        this.events = events;
        this.clock = clock;
    }

    public Optional<IntentResult> answer(String text, Runnable beforeMutation) {
        if (text == null || text.isBlank()) return Optional.empty();
        Matcher title = TITLE.matcher(text.strip());
        Matcher detail = DETAIL.matcher(text.strip());
        boolean updatesTitle = title.matches();
        Matcher matched = updatesTitle ? title : detail;
        if (!updatesTitle && !detail.matches()) return Optional.empty();

        int ordinal = ordinal(matched.group(1));
        Long id = context.objectAnnotationIdAt(ordinal);
        ObjectAnnotation annotation = id == null ? null : repository.findById(id).orElse(null);
        if (annotation == null || annotation.isArchived()) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "找不到上一份知識清單的第 %d 筆；請先重新查詢後再編修。".formatted(ordinal)));
        }
        String replacement = matched.group(2).strip();
        if (replacement.isBlank()) {
            return Optional.of(IntentResult.clarificationNeeded("請提供要更新的知識內容。"));
        }
        beforeMutation.run();
        Instant now = Instant.now(clock);
        annotation.update(updatesTitle ? replacement : annotation.getSubject(),
                updatesTitle ? annotation.getDetail() : replacement, now);
        events.publishEvent(new ObjectAnnotationUpdatedEvent(id, annotation.getSubject(), now));
        return Optional.of(IntentResult.message(IntentResult.Action.TAGGED_RECORDS_INFO,
                "已更新第 %d 筆知識紀錄「%s」。建立時間保留不變，最後修改時間已更新。"
                        .formatted(ordinal, annotation.getSubject())));
    }

    private static int ordinal(String value) {
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        if ("十".equals(value)) return 10;
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digit(value.charAt(0));
            int ones = ten == value.length() - 1 ? 0 : digit(value.charAt(ten + 1));
            return tens * 10 + ones;
        }
        return digit(value.charAt(0));
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }
}
