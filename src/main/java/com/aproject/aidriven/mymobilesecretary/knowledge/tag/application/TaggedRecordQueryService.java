package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.application.PriceRecordService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagBinding;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence.TaggedLifeRecordRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves graph bindings into actor-visible purchase and generic life-record projections. */
@Service
@Transactional(readOnly = true)
public class TaggedRecordQueryService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final SemanticTagGraphService graphService;
    private final PriceRecordService priceRecordService;
    private final TaggedLifeRecordRepository lifeRecordRepository;
    private final ObjectAnnotationRepository annotationRepository;

    public TaggedRecordQueryService(SemanticTagGraphService graphService,
                                    PriceRecordService priceRecordService,
                                    TaggedLifeRecordRepository lifeRecordRepository,
                                    ObjectAnnotationRepository annotationRepository) {
        this.graphService = graphService;
        this.priceRecordService = priceRecordService;
        this.lifeRecordRepository = lifeRecordRepository;
        this.annotationRepository = annotationRepository;
    }

    public List<TaggedRecordView> query(String keyword, Instant from, Instant to, String filter) {
        if ((from == null) != (to == null) || from != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("tag query date range is invalid");
        }
        String normalizedFilter = filter == null ? null : filter.strip().toUpperCase();
        List<SemanticTagBinding> bindings = java.util.stream.Stream.concat(
                        graphService.findRelatedBindings(keyword).stream(),
                        graphService.findRelatedBindingsInPhrase(keyword).stream())
                .collect(java.util.stream.Collectors.toMap(
                        binding -> binding.getTargetType() + ":" + binding.getTargetId(),
                        java.util.function.Function.identity(), (left, right) -> left))
                .values().stream().toList();
        List<TaggedRecordView> result = new ArrayList<>();
        if (normalizedFilter == null || normalizedFilter.equals("KNOWLEDGE")) {
            List<Long> ids = bindings.stream()
                    .filter(binding -> binding.getTargetType()
                            == SemanticTagBinding.TargetType.OBJECT_ANNOTATION)
                    .map(SemanticTagBinding::getTargetId).distinct().toList();
            annotationRepository.findAllById(ids).stream()
                    .filter(annotation -> !annotation.isArchived())
                    .filter(annotation -> within(annotation.getCreatedAt(), from, to))
                    .forEach(annotation -> result.add(annotationView(annotation)));
        }
        if (normalizedFilter == null || normalizedFilter.equals("PURCHASE")) {
            List<Long> ids = bindings.stream()
                    .filter(binding -> binding.getTargetType()
                            == SemanticTagBinding.TargetType.PRICE_RECORD)
                    .map(SemanticTagBinding::getTargetId).distinct().toList();
            priceRecordService.findByIds(ids).stream()
                    .filter(record -> {
                        Instant occurredAt = record.getPurchasedAt()
                                .atStartOfDay(TAIPEI).toInstant();
                        return from == null || !occurredAt.isBefore(from) && occurredAt.isBefore(to);
                    })
                    .forEach(record -> result.add(new TaggedRecordView("PURCHASE",
                            record.getItemName(), record.getPurchasedAt().atStartOfDay(TAIPEI).toInstant(),
                            "%s｜%d 元".formatted(record.getStoreName(), record.getTotalPriceTwd()),
                            null)));
        }
        if (normalizedFilter == null || !normalizedFilter.equals("PURCHASE")) {
            List<Long> ids = bindings.stream()
                    .filter(binding -> binding.getTargetType()
                            == SemanticTagBinding.TargetType.LIFE_RECORD)
                    .map(SemanticTagBinding::getTargetId).distinct().toList();
            List<TaggedLifeRecord> records = ids.isEmpty() ? List.of()
                    : from == null ? lifeRecordRepository.findByIdInOrderByOccurredAtDesc(ids)
                    : lifeRecordRepository.findByIdInAndOccurredAtBetweenOrderByOccurredAtDesc(
                            ids, from, to);
            records.stream()
                    .filter(record -> !record.getTitle().equals("商品註記"))
                    .filter(record -> record.getRecordType()
                            != TaggedLifeRecord.RecordType.USER_UTTERANCE)
                    .filter(record -> normalizedFilter == null
                            || record.getRecordType().name().equals(normalizedFilter))
                    .forEach(record -> result.add(new TaggedRecordView(
                            record.getRecordType().name(), record.getTitle(),
                            record.getOccurredAt(), record.getDetails(), null)));
        }
        return result.stream().sorted(Comparator.comparing(
                TaggedRecordView::occurredAt).reversed()).limit(20).toList();
    }

    private static boolean within(Instant occurredAt, Instant from, Instant to) {
        return from == null || !occurredAt.isBefore(from) && occurredAt.isBefore(to);
    }

    private static TaggedRecordView annotationView(ObjectAnnotation annotation) {
        return new TaggedRecordView("KNOWLEDGE", annotation.getSubject(),
                annotation.getCreatedAt(), annotation.getDetail(), annotation.getId());
    }

    public record TaggedRecordView(String type, String title, Instant occurredAt, String details,
                                   Long objectAnnotationId) {
        public TaggedRecordView(String type, String title, Instant occurredAt, String details) {
            this(type, title, occurredAt, details, null);
        }
    }
}
