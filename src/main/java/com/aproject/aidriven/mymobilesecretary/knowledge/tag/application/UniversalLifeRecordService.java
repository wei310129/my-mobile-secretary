package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.ConsumptionTagCatalog;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ExpenseCategory;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Records every non-development user utterance once and gives it at least one graph tag. */
@Service
public class UniversalLifeRecordService {

    private static final Pattern ORGANIZATION = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9]{1,20}(?:牙醫|診所|醫院|電子|公司|銀行|商店|餐廳))");
    private static final Pattern BENEFIT = Pattern.compile(
            "(?:節能|政府|假牙|育兒|租屋|學費|醫療|交通|長照|托育)?補助");
    private static final Pattern SENSITIVE_ID = Pattern.compile(
            "[A-Z][12]\\d{8}|\\b09\\d{8}\\b|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}",
            Pattern.CASE_INSENSITIVE);
    private static final List<String> PRODUCTS = List.of(
            "冰箱", "冷氣", "洗衣機", "電視", "電腦", "手機", "假牙", "電器", "家電");

    private final SemanticTagGraphService graphService;
    private final ConsumptionTagCatalog consumptionTagCatalog;
    private final Clock clock;

    public UniversalLifeRecordService(SemanticTagGraphService graphService,
                                      ConsumptionTagCatalog consumptionTagCatalog,
                                      Clock clock) {
        this.graphService = graphService;
        this.consumptionTagCatalog = consumptionTagCatalog;
        this.clock = clock;
    }

    public void recordUtterance(String text, IntentResult result) {
        if (text == null || text.isBlank() || result == null
                || result.action() == IntentResult.Action.FEEDBACK_RECEIVED) {
            return;
        }
        Set<TagSeed> seeds = extractSeeds(text, result.action());
        String title = safeTitle(text);
        List<SemanticTagGraphService.TagSpec> tags = seeds.stream()
                .map(seed -> new SemanticTagGraphService.TagSpec(
                        seed.name(), seed.kind(), SemanticTagEdge.SourceType.SYSTEM_RULE))
                .toList();
        graphService.recordLifeEvent(TaggedLifeRecord.RecordType.USER_UTTERANCE,
                title, Instant.now(clock), null, tags);
    }

    public void recordDomainEvent(TaggedLifeRecord.RecordType type, String title,
                                  Instant occurredAt, List<String> tagNames) {
        DomainRecord record = domainRecord(type, title, occurredAt, tagNames);
        graphService.recordLifeEvent(
                record.type(), record.title(), record.occurredAt(), null, record.tags());
    }

    public void recordDomainEventOnce(
            String sourceEventIdentity,
            TaggedLifeRecord.RecordType type,
            String title,
            Instant occurredAt,
            List<String> tagNames) {
        if (sourceEventIdentity == null || sourceEventIdentity.isBlank()) {
            throw new IllegalArgumentException("source event identity is required");
        }
        DomainRecord record = domainRecord(type, title, occurredAt, tagNames);
        String canonicalTags =
                record.tags().stream()
                        .map(
                                spec ->
                                        spec.name()
                                                + "\u001f"
                                                + spec.kind().name()
                                                + "\u001f"
                                                + spec.source().name())
                        .collect(java.util.stream.Collectors.joining("\u001e"));
        String payload =
                record.type().name()
                        + "\n"
                        + record.title()
                        + "\n"
                        + record.occurredAt()
                        + "\n"
                        + canonicalTags;
        graphService.recordLifeEventOnce(
                record.type(),
                record.title(),
                record.occurredAt(),
                null,
                record.tags(),
                sha256(sourceEventIdentity.strip()),
                sha256(payload));
    }

    private DomainRecord domainRecord(
            TaggedLifeRecord.RecordType type,
            String title,
            Instant occurredAt,
            List<String> tagNames) {
        TaggedLifeRecord.RecordType safeType =
                Objects.requireNonNull(type, "record type is required");
        String safeTitle = safeTitle(title);
        Instant effectiveOccurredAt = occurredAt == null ? Instant.now(clock) : occurredAt;
        List<SemanticTagGraphService.TagSpec> tags = new ArrayList<>();
        tags.add(new SemanticTagGraphService.TagSpec(
                "生活事件", SemanticTag.Kind.TOPIC, SemanticTagEdge.SourceType.SYSTEM_RULE));
        for (String tagName : tagNames == null ? List.<String>of() : tagNames) {
            tags.add(new SemanticTagGraphService.TagSpec(
                    tagName, SemanticTag.Kind.ACTIVITY, SemanticTagEdge.SourceType.SYSTEM_RULE));
        }
        extractSeeds(title, null).stream()
                .map(seed -> new SemanticTagGraphService.TagSpec(
                        seed.name(), seed.kind(), SemanticTagEdge.SourceType.SYSTEM_RULE))
                .forEach(tags::add);
        List<SemanticTagGraphService.TagSpec> canonicalTags =
                tags.stream()
                        .map(
                                spec ->
                                        new SemanticTagGraphService.TagSpec(
                                                spec.name().strip(),
                                                spec.kind(),
                                                spec.source()))
                        .distinct()
                        .sorted(
                                Comparator.comparing(SemanticTagGraphService.TagSpec::name)
                                        .thenComparing(spec -> spec.kind().name())
                                        .thenComparing(spec -> spec.source().name()))
                        .toList();
        return new DomainRecord(safeType, safeTitle, effectiveOccurredAt, canonicalTags);
    }

    private Set<TagSeed> extractSeeds(String text, IntentResult.Action action) {
        Set<TagSeed> seeds = new LinkedHashSet<>();
        seeds.add(new TagSeed("生活紀錄", SemanticTag.Kind.TOPIC));
        if (action != null) {
            seeds.add(new TagSeed(action.name(), SemanticTag.Kind.ACTIVITY));
        }
        ExpenseCategory category = consumptionTagCatalog.classify(text, null).category();
        if (category != ExpenseCategory.UNKNOWN) {
            String name = category == ExpenseCategory.ELECTRONICS
                    ? "電器" : category.displayName();
            seeds.add(new TagSeed(name, SemanticTag.Kind.CATEGORY));
        }
        addMatches(seeds, ORGANIZATION.matcher(text), SemanticTag.Kind.ORGANIZATION);
        addMatches(seeds, BENEFIT.matcher(text), SemanticTag.Kind.BENEFIT);
        for (String product : PRODUCTS) {
            if (text.contains(product)) seeds.add(new TagSeed(product, SemanticTag.Kind.PRODUCT));
        }
        if (!isSensitiveMedical(text)) {
            seeds.add(new TagSeed(truncate(SENSITIVE_ID.matcher(text).replaceAll("***"), 120),
                    SemanticTag.Kind.TOPIC));
        }
        return seeds;
    }

    private static void addMatches(Set<TagSeed> seeds, Matcher matcher, SemanticTag.Kind kind) {
        while (matcher.find()) seeds.add(new TagSeed(matcher.group(), kind));
    }

    private static String safeTitle(String text) {
        if (isSensitiveMedical(text)) return "醫療相關使用者輸入";
        return truncate(SENSITIVE_ID.matcher(text.strip()).replaceAll("***"), 200);
    }

    private static boolean isSensitiveMedical(String text) {
        return text.contains("診斷") || text.contains("病歷") || text.contains("健保號")
                || text.contains("身分證") || text.contains("藥物") || text.contains("醫囑");
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record DomainRecord(
            TaggedLifeRecord.RecordType type,
            String title,
            Instant occurredAt,
            List<SemanticTagGraphService.TagSpec> tags) {}

    private record TagSeed(String name, SemanticTag.Kind kind) {
    }
}
