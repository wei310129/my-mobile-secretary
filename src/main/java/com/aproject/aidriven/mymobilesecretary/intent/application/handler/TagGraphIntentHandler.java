package com.aproject.aidriven.mymobilesecretary.intent.application.handler;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.TaggedRecordQueryService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Deterministic user-managed tag graph and graph-backed life-record queries. */
@Component
@RequiredArgsConstructor
public final class TagGraphIntentHandler implements IntentHandler {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Set<IntentCommand.Type> TYPES = Set.of(
            IntentCommand.Type.UPSERT_TAG_RELATION,
            IntentCommand.Type.RECORD_TAGGED_LIFE_EVENT,
            IntentCommand.Type.ASK_TAGGED_RECORDS);

    private final SemanticTagGraphService graphService;
    private final TaggedRecordQueryService queryService;

    @Override
    public Set<IntentCommand.Type> supportedTypes() {
        return TYPES;
    }

    @Override
    public IntentResult handle(String text, IntentCommand command) {
        try {
            return switch (command.type()) {
                case UPSERT_TAG_RELATION -> saveRelation(command);
                case RECORD_TAGGED_LIFE_EVENT -> recordEvent(command);
                case ASK_TAGGED_RECORDS -> query(command);
                default -> throw new IllegalArgumentException(
                        "unsupported tag graph intent type " + command.type());
            };
        } catch (IllegalArgumentException exception) {
            return IntentHandlerExceptionMapper.clarification(exception);
        }
    }

    private IntentResult saveRelation(IntentCommand command) {
        require(command.title(), "from tag");
        IntentOptions options = command.safeOptions();
        require(options.referenceTitle(), "to tag");
        SemanticTagEdge.RelationType relation = parseRelation(options.referenceKind());
        graphService.relate(command.title(), parseKind(options.category()), relation,
                options.referenceTitle(), parseKind(options.filter()),
                SemanticTagEdge.SourceType.USER);
        return IntentResult.message(IntentResult.Action.TAG_RELATION_SAVED,
                "已建立標籤關係：「%s」%s「%s」。".formatted(
                        command.title(), display(relation), options.referenceTitle()));
    }

    private IntentResult recordEvent(IntentCommand command) {
        require(command.title(), "life record title");
        Instant occurredAt = parseRequired(command.startAt(), "occurredAt");
        IntentOptions options = command.safeOptions();
        if (options.itemNames() == null || options.itemNames().isEmpty()) {
            throw new IllegalArgumentException("at least one life record tag is required");
        }
        TaggedLifeRecord.RecordType type = parseRecordType(options.category());
        List<SemanticTagGraphService.TagSpec> tags = options.itemNames().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new SemanticTagGraphService.TagSpec(
                        name, inferKind(name), SemanticTagEdge.SourceType.USER))
                .toList();
        graphService.recordLifeEvent(type, command.title(), occurredAt,
                options.description(), tags);
        return IntentResult.message(IntentResult.Action.TAGGED_LIFE_EVENT_RECORDED,
                "已記錄「%s」（%s），並建立 %d 個標籤。".formatted(
                        command.title(), DATE.format(occurredAt.atZone(TAIPEI)), tags.size()));
    }

    private IntentResult query(IntentCommand command) {
        require(command.title(), "tag query keyword");
        String keyword = lookupKeyword(command.title());
        Instant from = parseOptional(command.startAt());
        Instant to = parseOptional(command.endAt());
        var records = queryService.query(
                keyword, from, to, command.safeOptions().filter());
        if (records.isEmpty()) {
            return IntentResult.message(IntentResult.Action.TAGGED_RECORDS_INFO,
                    "目前沒有標記為「%s」或其關聯標籤的紀錄。".formatted(keyword));
        }
        String lines = records.stream().map(record -> "%s｜%s｜%s%s".formatted(
                        DATE.format(record.occurredAt().atZone(TAIPEI)), record.type(),
                        record.title(), record.details() == null ? "" : "｜" + record.details()))
                .collect(Collectors.joining("\n"));
        return IntentResult.message(IntentResult.Action.TAGGED_RECORDS_INFO,
                "找到 %d 筆「%s」相關紀錄：\n%s".formatted(
                        records.size(), keyword, lines));
    }

    private static String lookupKeyword(String value) {
        String keyword = value.strip().replaceFirst(
                "^(?:幫我)?(?:查我記過的|查一下|查看|查)", "").strip();
        return keyword.isBlank() ? value.strip() : keyword;
    }

    private static SemanticTag.Kind inferKind(String name) {
        if (name.endsWith("補助")) return SemanticTag.Kind.BENEFIT;
        if (name.endsWith("公司") || name.endsWith("電子") || name.endsWith("牙醫")
                || name.endsWith("診所") || name.endsWith("醫院")) {
            return SemanticTag.Kind.ORGANIZATION;
        }
        if (Set.of("冰箱", "冷氣", "洗衣機", "電器", "家電", "假牙").contains(name)) {
            return SemanticTag.Kind.PRODUCT;
        }
        return SemanticTag.Kind.TOPIC;
    }

    private static SemanticTag.Kind parseKind(String value) {
        if (value == null || value.isBlank()) return SemanticTag.Kind.OTHER;
        try {
            return SemanticTag.Kind.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported tag kind: " + value, exception);
        }
    }

    private static SemanticTagEdge.RelationType parseRelation(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tag relation type is required");
        }
        try {
            return SemanticTagEdge.RelationType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported tag relation: " + value, exception);
        }
    }

    private static TaggedLifeRecord.RecordType parseRecordType(String value) {
        if (value == null || value.isBlank()) return TaggedLifeRecord.RecordType.OTHER;
        try {
            return TaggedLifeRecord.RecordType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported life record type: " + value, exception);
        }
    }

    private static Instant parseRequired(String value, String field) {
        Instant parsed = parseOptional(value);
        if (parsed == null) throw new IllegalArgumentException(field + " is required");
        return parsed;
    }

    private static Instant parseOptional(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("invalid tag record time", exception);
        }
    }

    private static String display(SemanticTagEdge.RelationType relation) {
        return switch (relation) {
            case IS_A -> " 是一種 ";
            case RELATED_TO -> " 關聯到 ";
            case PART_OF -> " 屬於 ";
            case ELIGIBLE_FOR -> " 可適用 ";
            case PROVIDED_BY -> " 由其提供 ";
        };
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
