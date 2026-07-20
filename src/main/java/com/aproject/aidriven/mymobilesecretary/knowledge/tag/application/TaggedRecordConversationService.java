package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.ConversationContextService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers natural-language lookups only when the private tag graph has actual matching records. */
@Service
@Transactional(readOnly = true)
public class TaggedRecordConversationService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Pattern LOOKUP = Pattern.compile(
            "^(?:請)?(?:幫我)?(?:查我記過的|查一下|查看|查|找)(.+)$");

    private final TaggedRecordQueryService queryService;
    private ConversationContextService conversationContextService;

    public TaggedRecordConversationService(TaggedRecordQueryService queryService) {
        this.queryService = queryService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setConversationContextService(ConversationContextService service) {
        this.conversationContextService = service;
    }

    public Optional<IntentResult> answer(String text) {
        if (text == null) return Optional.empty();
        var matcher = LOOKUP.matcher(text.strip());
        if (!matcher.matches()) return Optional.empty();
        String keyword = matcher.group(1).strip();
        if (keyword.isBlank()) return Optional.empty();
        var records = queryService.query(keyword, null, null, null);
        if (records.isEmpty()) return Optional.empty();
        int tagRelevance = 60 + score(text, "註記", "標籤", "記過", "紀錄", "記得") * 15;
        int purchaseRelevance = score(text, "購買", "買的", "買過", "價格", "多少錢", "庫存", "店家") * 70;
        if (purchaseRelevance > tagRelevance + 10) return Optional.empty();
        if (purchaseRelevance > 0 && Math.abs(tagRelevance - purchaseRelevance) <= 10) {
            return Optional.of(IntentResult.clarificationNeeded(
                    "「%s」同時命中已存註記與購買查詢線索。你要查商品註記，還是購買／價格紀錄？"
                            .formatted(keyword)));
        }
        if (conversationContextService != null) {
            conversationContextService.rememberObjectAnnotationList(records.stream()
                    .map(TaggedRecordQueryService.TaggedRecordView::objectAnnotationId)
                    .map(id -> id == null ? 0L : id).toList());
        }
        String lines = java.util.stream.IntStream.range(0, records.size())
                .mapToObj(index -> {
                    var record = records.get(index);
                    return "%d. %s｜%s｜%s%s".formatted(index + 1,
                            DATE.format(record.occurredAt().atZone(TAIPEI)), record.type(),
                            record.title(), record.details() == null ? "" : "｜" + record.details());
                }).collect(Collectors.joining("\n"));
        return Optional.of(IntentResult.message(IntentResult.Action.TAGGED_RECORDS_INFO,
                "找到 %d 筆「%s」相關紀錄：\n%s".formatted(records.size(), keyword, lines)));
    }

    private static int score(String text, String... cues) {
        int score = 0;
        for (String cue : cues) {
            if (text.contains(cue)) score++;
        }
        return score;
    }
}
