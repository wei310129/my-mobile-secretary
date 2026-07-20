package com.aproject.aidriven.mymobilesecretary.knowledge.tag.application;

import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ExpenseCategory;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PriceRecord;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagAlias;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagBinding;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence.SemanticTagAliasRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence.SemanticTagBindingRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence.SemanticTagEdgeRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence.SemanticTagRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.persistence.TaggedLifeRecordRepository;
import com.aproject.aidriven.mymobilesecretary.media.domain.StoredMedia;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Actor-isolated tag graph: nodes, aliases, typed edges and bindings to life records. */
@Service
@Transactional
public class SemanticTagGraphService {

    private static final int MAX_QUERY_DEPTH = 4;
    private static final Map<ExpenseCategory, String> EXPENSE_TAG_NAMES = Map.ofEntries(
            Map.entry(ExpenseCategory.FOOD, "餐飲"),
            Map.entry(ExpenseCategory.BEVERAGE, "飲品"),
            Map.entry(ExpenseCategory.HOUSEHOLD, "生活用品"),
            Map.entry(ExpenseCategory.EDUCATION, "教育學習"),
            Map.entry(ExpenseCategory.CHILDCARE, "育兒"),
            Map.entry(ExpenseCategory.ENTERTAINMENT, "娛樂"),
            Map.entry(ExpenseCategory.TRANSPORT, "交通"),
            Map.entry(ExpenseCategory.HEALTHCARE, "醫療保健"),
            Map.entry(ExpenseCategory.CLOTHING, "服飾"),
            Map.entry(ExpenseCategory.LUXURY, "精品首飾"),
            Map.entry(ExpenseCategory.ELECTRONICS, "電器"),
            Map.entry(ExpenseCategory.HOUSING, "居家"),
            Map.entry(ExpenseCategory.WORK, "工作"),
            Map.entry(ExpenseCategory.TAX, "稅款"),
            Map.entry(ExpenseCategory.OTHER, "其他"),
            Map.entry(ExpenseCategory.UNKNOWN, "未分類"));

    private final SemanticTagRepository tagRepository;
    private final SemanticTagAliasRepository aliasRepository;
    private final SemanticTagEdgeRepository edgeRepository;
    private final SemanticTagBindingRepository bindingRepository;
    private final TaggedLifeRecordRepository lifeRecordRepository;
    private final Clock clock;

    public SemanticTagGraphService(
            SemanticTagRepository tagRepository,
            SemanticTagAliasRepository aliasRepository,
            SemanticTagEdgeRepository edgeRepository,
            SemanticTagBindingRepository bindingRepository,
            TaggedLifeRecordRepository lifeRecordRepository,
            Clock clock) {
        this.tagRepository = tagRepository;
        this.aliasRepository = aliasRepository;
        this.edgeRepository = edgeRepository;
        this.bindingRepository = bindingRepository;
        this.lifeRecordRepository = lifeRecordRepository;
        this.clock = clock;
    }

    public SemanticTag ensureTag(String name, SemanticTag.Kind kind) {
        String normalized = SemanticTag.normalize(name);
        return tagRepository.findByNormalizedNameAndKind(normalized, kind)
                .orElseGet(() -> tagRepository.save(
                        SemanticTag.create(name, kind, Instant.now(clock))));
    }

    public void addAlias(SemanticTag tag, String alias) {
        String normalized = SemanticTag.normalize(alias);
        if (normalized.equals(tag.getNormalizedName())
                || aliasRepository.existsByNormalizedAlias(normalized)) {
            return;
        }
        aliasRepository.save(SemanticTagAlias.create(tag.getId(), alias, Instant.now(clock)));
    }

    public SemanticTagEdge relate(String fromName, SemanticTag.Kind fromKind,
                                  SemanticTagEdge.RelationType relation,
                                  String toName, SemanticTag.Kind toKind,
                                  SemanticTagEdge.SourceType source) {
        SemanticTag from = ensureTag(fromName, fromKind);
        SemanticTag to = ensureTag(toName, toKind);
        if (from.getId().equals(to.getId())) {
            throw new IllegalArgumentException("a tag cannot relate to itself");
        }
        if (edgeRepository.existsByFromTagIdAndToTagIdAndRelationType(
                from.getId(), to.getId(), relation)) {
            return edgeRepository.findAll().stream()
                    .filter(edge -> edge.getFromTagId().equals(from.getId())
                            && edge.getToTagId().equals(to.getId())
                            && edge.getRelationType() == relation)
                    .findFirst().orElseThrow();
        }
        return edgeRepository.save(SemanticTagEdge.create(
                from.getId(), to.getId(), relation, source, Instant.now(clock)));
    }

    public TaggedLifeRecord recordLifeEvent(TaggedLifeRecord.RecordType type, String title,
                                            Instant occurredAt, String details,
                                            List<TagSpec> tags) {
        TaggedLifeRecord record = lifeRecordRepository.save(TaggedLifeRecord.create(
                type, title, occurredAt, details, Instant.now(clock)));
        for (TagSpec spec : tags == null ? List.<TagSpec>of() : tags) {
            bind(ensureTag(spec.name(), spec.kind()),
                    SemanticTagBinding.TargetType.LIFE_RECORD, record.getId(), spec.source());
        }
        return record;
    }

    public void indexPriceRecord(PriceRecord record) {
        SemanticTag item = ensureTag(record.getItemName(), SemanticTag.Kind.PRODUCT);
        SemanticTag category = ensureTag(EXPENSE_TAG_NAMES.get(record.getExpenseCategory()),
                SemanticTag.Kind.CATEGORY);
        bind(item, SemanticTagBinding.TargetType.PRICE_RECORD, record.getId(),
                SemanticTagEdge.SourceType.IMPORT);
        bind(category, SemanticTagBinding.TargetType.PRICE_RECORD, record.getId(),
                SemanticTagEdge.SourceType.SYSTEM_RULE);
        relate(item.getCanonicalName(), item.getKind(), SemanticTagEdge.RelationType.IS_A,
                category.getCanonicalName(), category.getKind(),
                SemanticTagEdge.SourceType.SYSTEM_RULE);
        if (record.getExpenseCategory() == ExpenseCategory.ELECTRONICS) {
            addAlias(category, "家電");
            addAlias(category, "家電數位");
        }
        if (record.getStoreName() != null && !record.getStoreName().isBlank()) {
            SemanticTag organization = ensureTag(
                    record.getStoreName(), SemanticTag.Kind.ORGANIZATION);
            bind(organization, SemanticTagBinding.TargetType.PRICE_RECORD, record.getId(),
                    SemanticTagEdge.SourceType.IMPORT);
            relate(organization.getCanonicalName(), organization.getKind(),
                    SemanticTagEdge.RelationType.RELATED_TO,
                    item.getCanonicalName(), item.getKind(), SemanticTagEdge.SourceType.IMPORT);
        }
    }

    /** Binds controlled, non-sensitive tags to an actor-private original media object. */
    public void indexStoredMedia(StoredMedia media, List<TagSpec> tags) {
        for (TagSpec spec : tags == null ? List.<TagSpec>of() : tags) {
            bind(ensureTag(spec.name(), spec.kind()),
                    SemanticTagBinding.TargetType.MEDIA, media.getId(), spec.source());
        }
    }

    /** Binds actor-private professional contacts to the same searchable tag graph. */
    public void indexExternalContact(Long contactId, List<TagSpec> tags) {
        for (TagSpec spec : tags == null ? List.<TagSpec>of() : tags) {
            bind(ensureTag(spec.name(), spec.kind()),
                    SemanticTagBinding.TargetType.EXTERNAL_CONTACT, contactId, spec.source());
        }
    }

    public void indexSchoolMeal(Long mealId, List<TagSpec> tags) {
        for (TagSpec spec : tags == null ? List.<TagSpec>of() : tags) {
            bind(ensureTag(spec.name(), spec.kind()),
                    SemanticTagBinding.TargetType.SCHOOL_MEAL, mealId, spec.source());
        }
    }

    public void indexBloodDonation(Long recordId, List<TagSpec> tags) {
        for (TagSpec spec : tags == null ? List.<TagSpec>of() : tags) {
            bind(ensureTag(spec.name(), spec.kind()),
                    SemanticTagBinding.TargetType.BLOOD_DONATION, recordId, spec.source());
        }
    }

    /** Binds arbitrary user labels to a generic annotation instead of adding object-specific columns. */
    public void indexObjectAnnotation(Long annotationId, List<TagSpec> tags) {
        for (TagSpec spec : tags == null ? List.<TagSpec>of() : tags) {
            bind(ensureTag(spec.name(), spec.kind()),
                    SemanticTagBinding.TargetType.OBJECT_ANNOTATION,
                    annotationId, spec.source());
        }
    }

    @Transactional(readOnly = true)
    public List<SemanticTagBinding> findRelatedBindings(String keyword) {
        Set<Long> reachable = resolveTagIds(keyword);
        return findRelatedBindings(reachable);
    }

    /** Resolves every existing tag contained in a natural-language lookup phrase. */
    public List<SemanticTagBinding> findRelatedBindingsInPhrase(String phrase) {
        String normalized = SemanticTag.normalize(phrase);
        Set<Long> matched = tagRepository.findAll().stream()
                .filter(tag -> tag.getNormalizedName().length() >= 2)
                .filter(tag -> normalized.contains(tag.getNormalizedName()))
                .map(SemanticTag::getId).collect(java.util.stream.Collectors.toSet());
        return findRelatedBindings(matched);
    }

    private List<SemanticTagBinding> findRelatedBindings(Set<Long> reachable) {
        if (reachable.isEmpty()) return List.of();
        List<SemanticTagEdge> edges = edgeRepository.findAll();
        for (int depth = 0; depth < MAX_QUERY_DEPTH; depth++) {
            Set<Long> additions = new HashSet<>();
            for (SemanticTagEdge edge : edges) {
                if (edge.getRelationType() == SemanticTagEdge.RelationType.IS_A) {
                    if (reachable.contains(edge.getToTagId())) additions.add(edge.getFromTagId());
                } else {
                    if (reachable.contains(edge.getFromTagId())) additions.add(edge.getToTagId());
                    if (reachable.contains(edge.getToTagId())) additions.add(edge.getFromTagId());
                }
            }
            if (!reachable.addAll(additions)) break;
        }
        return bindingRepository.findByTagIdIn(reachable);
    }

    private Set<Long> resolveTagIds(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("tag query keyword is required");
        }
        String normalized = SemanticTag.normalize(keyword);
        Set<Long> ids = new HashSet<>();
        tagRepository.findByNormalizedName(normalized).forEach(tag -> ids.add(tag.getId()));
        aliasRepository.findByNormalizedAlias(normalized)
                .ifPresent(alias -> ids.add(alias.getTagId()));
        return ids;
    }

    private void bind(SemanticTag tag, SemanticTagBinding.TargetType targetType,
                      Long targetId, SemanticTagEdge.SourceType source) {
        if (!bindingRepository.existsByTagIdAndTargetTypeAndTargetId(
                tag.getId(), targetType, targetId)) {
            bindingRepository.save(SemanticTagBinding.create(
                    tag.getId(), targetType, targetId, source, Instant.now(clock)));
        }
    }

    public record TagSpec(String name, SemanticTag.Kind kind,
                          SemanticTagEdge.SourceType source) {
        public TagSpec {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("tag name is required");
            kind = kind == null ? SemanticTag.Kind.OTHER : kind;
            source = source == null ? SemanticTagEdge.SourceType.USER : source;
        }
    }
}
