package com.aproject.aidriven.mymobilesecretary.knowledge.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.draft.application.DraftRetentionService;
import com.aproject.aidriven.mymobilesecretary.draft.domain.DraftRetentionBinding.DraftType;
import com.aproject.aidriven.mymobilesecretary.intent.application.ReceiptCommand.PaintProductInfo;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation.TargetType;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ProductObservationDraft;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ProductObservationDraft.Status;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact.Category;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ProductObservationDraftRepository;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.application.SemanticTagGraphService;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTag;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.SemanticTagEdge;
import com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain.TaggedLifeRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converts explicit product experience into private knowledge and deterministic cautions. */
@Service
@Transactional
public class ProductExperienceService {
    private final ProductObservationDraftRepository drafts;
    private final ObjectAnnotationRepository annotations;
    private final UserKnowledgeService knowledge;
    private final SemanticTagGraphService graph;
    private final Clock clock;
    private DraftRetentionService draftRetention;

    public ProductExperienceService(ProductObservationDraftRepository drafts,
                                    ObjectAnnotationRepository annotations,
                                    UserKnowledgeService knowledge,
                                    SemanticTagGraphService graph, Clock clock) {
        this.drafts = drafts;
        this.annotations = annotations;
        this.knowledge = knowledge;
        this.graph = graph;
        this.clock = clock;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDraftRetention(DraftRetentionService draftRetention) {
        this.draftRetention = draftRetention;
    }

    public ProductObservationDraft capture(PaintProductInfo info, String sourceTitle) {
        if (info == null) throw new IllegalArgumentException("paint product facts are required");
        Instant now = Instant.now(clock);
        String product = blank(info.productName()) ? "油漆" : info.productName();
        ProductObservationDraft saved = drafts.save(ProductObservationDraft.create(
                product, info.brandName(),
                combinedColor(info), sourceTitle, now,
                DraftRetentionService.expiresAt(now)));
        indexPublicTaxonomy(info, product);
        if (draftRetention != null) {
            draftRetention.register(DraftType.PRODUCT_OBSERVATION,
                    saved.getId(), saved.displayName());
        }
        return saved;
    }

    private void indexPublicTaxonomy(PaintProductInfo info, String product) {
        List<String> systemTags = product.contains("漆")
                ? List.of("油漆", "塗料", "居家修繕", "牆面施工", "室內裝修", "DIY")
                : List.of();
        systemTags.forEach(value -> relatePublicTag(
                product, value, SemanticTagEdge.SourceType.SYSTEM_RULE));
        cleanPublicTags(info.publicTags()).forEach(value -> relatePublicTag(
                product, value, SemanticTagEdge.SourceType.IMPORT));
    }

    private void relatePublicTag(String product, String publicTag,
                                 SemanticTagEdge.SourceType source) {
        if (SemanticTag.normalize(product).equals(SemanticTag.normalize(publicTag))) return;
        graph.relate(product, SemanticTag.Kind.PRODUCT,
                SemanticTagEdge.RelationType.RELATED_TO,
                publicTag, SemanticTag.Kind.TOPIC, source);
    }

    private static List<String> cleanPublicTags(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .filter(value -> !blank(value))
                .map(String::strip)
                .filter(value -> value.length() <= 30)
                .filter(value -> value.matches("[\\p{L}\\p{N} _+#&/.-]+"))
                .filter(value -> !containsPrivateClaim(value))
                .distinct().limit(8).toList();
    }

    private static boolean containsPrivateClaim(String value) {
        return List.of("推薦", "過敏", "不適", "提醒", "客廳", "房間", "施工批次")
                .stream().anyMatch(value::contains);
    }

    public String retentionDisclosure(ProductObservationDraft draft) {
        if (draftRetention != null) {
            return draftRetention.disclose(DraftType.PRODUCT_OBSERVATION, draft.getId());
        }
        return "這份草稿會保留到 %s 24:00，之後刪除。"
                .formatted(DraftRetentionService.expiryDay(draft.getExpiresAt()));
    }

    public RecordedExperience recordLatest(ExperienceKind kind, String explicitDetail) {
        if (kind == null || blank(explicitDetail)) {
            throw new IllegalArgumentException("explicit product experience is required");
        }
        Instant now = Instant.now(clock);
        ProductObservationDraft draft = drafts
                .findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        WorkspaceContextHolder.requireContext().actorId(),
                        Status.PENDING_PURPOSE, now)
                .orElseThrow(() -> new IllegalStateException("no pending product image exists"));
        List<String> labels = switch (kind) {
            case USAGE -> List.of("使用用途");
            case RECOMMENDATION -> List.of("朋友推薦");
            case CAUTION -> List.of("使用後不適", "購物提醒");
        };
        ObjectAnnotation annotation = saveAnnotation(
                draft, explicitDetail, labels, kind == ExperienceKind.CAUTION, now);
        draft.resolve(now);
        drafts.save(draft);
        if (draftRetention != null) {
            draftRetention.complete(DraftType.PRODUCT_OBSERVATION, draft.getId());
        }
        return new RecordedExperience(draft, annotation, kind);
    }

    /** Records arbitrary user labels without adding product-specific columns or enum categories. */
    public RecordedExperience recordLatestAnnotation(List<String> labels, String explicitDetail,
                                                     boolean purchaseReminder) {
        List<String> cleanLabels = (labels == null ? List.<String>of() : labels).stream()
                .filter(value -> !blank(value)).map(String::strip).distinct().limit(12).toList();
        if (cleanLabels.isEmpty() || blank(explicitDetail)) {
            throw new IllegalArgumentException("annotation labels and detail are required");
        }
        Instant now = Instant.now(clock);
        ProductObservationDraft draft = latestAnnotatable(now);
        ObjectAnnotation annotation = saveAnnotation(
                draft, explicitDetail, cleanLabels, purchaseReminder, now);
        if (draft.getStatus() == Status.PENDING_PURPOSE) {
            draft.resolve(now);
            drafts.save(draft);
            if (draftRetention != null) {
                draftRetention.complete(DraftType.PRODUCT_OBSERVATION, draft.getId());
            }
        }
        return new RecordedExperience(draft, annotation, null);
    }

    /** Converts the latest pending OCR draft into durable private knowledge without inventing facts. */
    public RecordedExperience completeLatestDraft() {
        Instant now = Instant.now(clock);
        ProductObservationDraft draft = latestPending(now);
        ObjectAnnotation annotation = saveAnnotation(draft,
                "商品圖片辨識資料：" + draft.displayName(), List.of("商品資料"), false, now);
        draft.resolve(now);
        drafts.save(draft);
        if (draftRetention != null) {
            draftRetention.complete(DraftType.PRODUCT_OBSERVATION, draft.getId());
        }
        return new RecordedExperience(draft, annotation, null);
    }

    @Transactional(readOnly = true)
    public List<PurchaseCaution> purchaseCautions(List<String> itemNames) {
        List<String> normalizedItems = (itemNames == null ? List.<String>of() : itemNames).stream()
                .filter(value -> !blank(value)).map(UserKnowledgeService::normalize).toList();
        if (normalizedItems.isEmpty()) return List.of();
        List<Long> annotationIds = graph.findRelatedBindings("購物提醒").stream()
                .filter(binding -> binding.getTargetType()
                        == com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain
                                .SemanticTagBinding.TargetType.OBJECT_ANNOTATION)
                .map(com.aproject.aidriven.mymobilesecretary.knowledge.tag.domain
                        .SemanticTagBinding::getTargetId)
                .distinct().toList();
        List<PurchaseCaution> tagged = annotations.findAllById(annotationIds).stream()
                .filter(annotation -> normalizedItems.stream()
                        .anyMatch(item -> matches(item, annotation.getSubject())))
                .map(annotation -> new PurchaseCaution(
                        annotation.getSubject(), annotation.getDetail()))
                .toList();
        List<PurchaseCaution> legacy = knowledge.list(Category.PRODUCT_CAUTION).stream()
                .filter(fact -> normalizedItems.stream().anyMatch(item -> matches(item, fact)))
                .map(fact -> new PurchaseCaution(fact.getSubject(), fact.getDetail()))
                .toList();
        return java.util.stream.Stream.concat(tagged.stream(), legacy.stream())
                .collect(java.util.stream.Collectors.toMap(
                        caution -> caution.product() + "\n" + caution.detail(),
                        java.util.function.Function.identity(), (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private ObjectAnnotation saveAnnotation(ProductObservationDraft draft, String detail,
                                            List<String> labels, boolean purchaseReminder,
                                            Instant now) {
        ObjectAnnotation annotation = annotations.save(ObjectAnnotation.create(
                TargetType.PRODUCT_OBSERVATION, draft.getId(), draft.displayName(), detail, now));
        List<SemanticTagGraphService.TagSpec> tags = new ArrayList<>();
        add(tags, draft.getProductName(), SemanticTag.Kind.PRODUCT);
        add(tags, draft.getBrandName(), SemanticTag.Kind.ORGANIZATION);
        add(tags, draft.getColorName(), SemanticTag.Kind.TOPIC);
        addProductLookupTags(tags, draft.getProductName(), draft.getBrandName());
        labels.forEach(label -> tags.add(tag(
                label, SemanticTag.Kind.TOPIC, SemanticTagEdge.SourceType.USER)));
        if (purchaseReminder && labels.stream().noneMatch("購物提醒"::equals)) {
            tags.add(tag("購物提醒", SemanticTag.Kind.TOPIC,
                    SemanticTagEdge.SourceType.USER));
        }
        graph.indexObjectAnnotation(annotation.getId(), tags);
        graph.recordLifeEvent(TaggedLifeRecord.RecordType.KNOWLEDGE,
                "商品註記", now, null, tags);
        return annotation;
    }

    /** Adds deterministic lookup terms so users do not need the full OCR-derived product title. */
    private static void addProductLookupTags(List<SemanticTagGraphService.TagSpec> tags,
                                             String productName, String brandName) {
        if (blank(productName)) return;
        String brandRoot = productBrandRoot(brandName);
        if (!blank(brandRoot)) {
            add(tags, brandRoot + productName, SemanticTag.Kind.PRODUCT);
        }
        if (!productName.contains("漆")) return;
        add(tags, "油漆", SemanticTag.Kind.PRODUCT);
        add(tags, "漆", SemanticTag.Kind.PRODUCT);
        if (!blank(brandRoot)) {
            add(tags, brandRoot + "油漆", SemanticTag.Kind.PRODUCT);
        }
    }

    private static String productBrandRoot(String brandName) {
        if (blank(brandName)) return null;
        String root = brandName.strip();
        for (String suffix : List.of("水泥漆", "油漆", "漆")) {
            if (root.endsWith(suffix) && root.length() > suffix.length()) {
                return root.substring(0, root.length() - suffix.length()).strip();
            }
        }
        return root;
    }

    private static boolean matches(String item, UserKnowledgeFact fact) {
        String subject = fact.getNormalizedSubject();
        return subject.contains(item) || item.contains(subject)
                || item.contains("漆") && subject.contains("漆");
    }

    private static boolean matches(String item, String subject) {
        String normalizedSubject = UserKnowledgeService.normalize(subject);
        return normalizedSubject.contains(item) || item.contains(normalizedSubject)
                || item.contains("漆") && normalizedSubject.contains("漆");
    }

    private ProductObservationDraft latestPending(Instant now) {
        return drafts.findFirstByCreatedByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
                        WorkspaceContextHolder.requireContext().actorId(),
                        Status.PENDING_PURPOSE, now)
                .orElseThrow(() -> new IllegalStateException("no pending product image exists"));
    }

    private ProductObservationDraft latestAnnotatable(Instant now) {
        return drafts.findFirstByCreatedByUserIdAndExpiresAtAfterOrderByCreatedAtDesc(
                        WorkspaceContextHolder.requireContext().actorId(), now)
                .orElseThrow(() -> new IllegalStateException("no recent product image exists"));
    }

    private static String combinedColor(PaintProductInfo info) {
        if (blank(info.colorName())) return info.colorCode();
        if (blank(info.colorCode())) return info.colorName();
        return info.colorName() + " " + info.colorCode();
    }

    private static void add(List<SemanticTagGraphService.TagSpec> tags,
                            String value, SemanticTag.Kind kind) {
        if (!blank(value)) tags.add(tag(value, kind, SemanticTagEdge.SourceType.IMPORT));
    }

    private static SemanticTagGraphService.TagSpec tag(
            String value, SemanticTag.Kind kind, SemanticTagEdge.SourceType source) {
        return new SemanticTagGraphService.TagSpec(value, kind, source);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public enum ExperienceKind { USAGE, RECOMMENDATION, CAUTION }
    public record RecordedExperience(ProductObservationDraft draft, ObjectAnnotation annotation,
                                     ExperienceKind kind) {}
    public record PurchaseCaution(String product, String detail) {}
}
