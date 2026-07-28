package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeCategory;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeEvidence;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeQuery;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeSourceType;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.PersonalKnowledgeRetriever;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.KnowledgeTextNormalizer;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * First retrieval implementation: bounded prefix lookup over actor-private JPA knowledge.
 * It deliberately excludes tasks, schedules, reminders, prices, places and stored-media bytes.
 */
@Component
@Transactional(readOnly = true)
public class JpaPersonalKnowledgeRetriever implements PersonalKnowledgeRetriever {

    static final Instant EARLIEST_CREATED_AT = Instant.parse("0001-01-01T00:00:00Z");
    static final Instant LATEST_CREATED_AT = Instant.parse("9999-12-31T23:59:59Z");

    private final UserKnowledgeFactRepository factRepository;
    private final ObjectAnnotationRepository annotationRepository;

    public JpaPersonalKnowledgeRetriever(UserKnowledgeFactRepository factRepository,
                                         ObjectAnnotationRepository annotationRepository) {
        this.factRepository = factRepository;
        this.annotationRepository = annotationRepository;
    }

    @Override
    public List<KnowledgeEvidence> retrieve(KnowledgeQuery query) {
        requireCurrentScope(query);
        String normalizedQuery = KnowledgeTextNormalizer.normalize(query.queryText());
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        Set<KnowledgeSourceType> sources = query.sourceTypes().isEmpty()
                ? EnumSet.allOf(KnowledgeSourceType.class)
                : query.sourceTypes();
        List<KnowledgeEvidence> evidence = new ArrayList<>();
        if (sources.contains(KnowledgeSourceType.USER_KNOWLEDGE_FACT)) {
            evidence.addAll(findFacts(query, normalizedQuery));
        }
        if (sources.contains(KnowledgeSourceType.OBJECT_ANNOTATION)
                && (query.categories().isEmpty()
                || query.categories().contains(KnowledgeCategory.OBJECT_ANNOTATION))) {
            evidence.addAll(findAnnotations(query, normalizedQuery));
        }

        Comparator<KnowledgeEvidence> deterministicOrder = Comparator
                .comparing((KnowledgeEvidence item) -> !KnowledgeTextNormalizer
                        .normalize(item.title()).equals(normalizedQuery))
                .thenComparing(KnowledgeEvidence::updatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(KnowledgeEvidence::evidenceId);
        return evidence.stream()
                .collect(Collectors.toMap(KnowledgeEvidence::evidenceId,
                        item -> item, (first, ignored) -> first))
                .values().stream()
                .sorted(deterministicOrder)
                .limit(query.limit())
                .toList();
    }

    private List<KnowledgeEvidence> findFacts(KnowledgeQuery query, String normalizedQuery) {
        Set<UserKnowledgeFact.Category> categories = query.categories().stream()
                .filter(category -> category != KnowledgeCategory.OBJECT_ANNOTATION)
                .map(category -> UserKnowledgeFact.Category.valueOf(category.name()))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(
                        UserKnowledgeFact.Category.class)));
        if (!query.categories().isEmpty() && categories.isEmpty()) {
            return List.of();
        }
        PageRequest page = PageRequest.of(0, query.limit());
        Instant createdFrom = createdFrom(query);
        Instant createdTo = createdTo(query);
        List<UserKnowledgeFact> facts = categories.isEmpty()
                ? factRepository.searchBySubjectPrefix(
                        query.workspaceId(), query.actorUserId(), normalizedQuery,
                        createdFrom, createdTo, page)
                : factRepository.searchBySubjectPrefixAndCategories(
                        query.workspaceId(), query.actorUserId(), normalizedQuery, categories,
                        createdFrom, createdTo, page);
        return facts.stream().map(this::toEvidence).toList();
    }

    private List<KnowledgeEvidence> findAnnotations(KnowledgeQuery query, String normalizedQuery) {
        PageRequest page = PageRequest.of(0, query.limit());
        Instant createdFrom = createdFrom(query);
        Instant createdTo = createdTo(query);
        String targetTypeValue = query.metadataFilter().get("targetType");
        List<ObjectAnnotation> annotations;
        if (targetTypeValue == null || targetTypeValue.isBlank()) {
            annotations = annotationRepository.searchActiveBySubjectPrefix(
                    query.workspaceId(), query.actorUserId(), normalizedQuery,
                    createdFrom, createdTo, page);
        } else {
            ObjectAnnotation.TargetType targetType;
            try {
                targetType = ObjectAnnotation.TargetType.valueOf(
                        targetTypeValue.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "unsupported knowledge targetType: " + targetTypeValue, exception);
            }
            annotations = annotationRepository.searchActiveBySubjectPrefixAndTargetType(
                    query.workspaceId(), query.actorUserId(), normalizedQuery, targetType,
                    createdFrom, createdTo, page);
        }
        return annotations.stream().map(this::toEvidence).toList();
    }

    private KnowledgeEvidence toEvidence(UserKnowledgeFact fact) {
        String id = String.valueOf(fact.getId());
        return new KnowledgeEvidence(
                KnowledgeSourceType.USER_KNOWLEDGE_FACT + ":" + id,
                KnowledgeSourceType.USER_KNOWLEDGE_FACT,
                id,
                fact.getSubject(),
                fact.getDetail(),
                Map.of("category", fact.getCategory().name()),
                fact.getCreatedAt(),
                fact.getUpdatedAt(),
                null,
                null,
                fact.getWorkspaceId(),
                fact.getCreatedByUserId(),
                true);
    }

    private KnowledgeEvidence toEvidence(ObjectAnnotation annotation) {
        String id = String.valueOf(annotation.getId());
        return new KnowledgeEvidence(
                KnowledgeSourceType.OBJECT_ANNOTATION + ":" + id,
                KnowledgeSourceType.OBJECT_ANNOTATION,
                id,
                annotation.getSubject(),
                annotation.getDetail(),
                Map.of("targetType", annotation.getTargetType().name(),
                        "targetId", annotation.getTargetId()),
                annotation.getCreatedAt(),
                annotation.getUpdatedAt(),
                null,
                null,
                annotation.getWorkspaceId(),
                annotation.getCreatedByUserId(),
                false);
    }

    private static void requireCurrentScope(KnowledgeQuery query) {
        var context = WorkspaceContextHolder.requireContext();
        if (!context.workspaceId().equals(query.workspaceId())
                || !context.actorId().equals(query.actorUserId())) {
            throw new SecurityException("knowledge query does not match the current actor scope");
        }
        // Shared workspace knowledge has no persistence visibility model yet. Even when the
        // caller opts in, this implementation remains actor-private instead of broadening access.
    }

    private static Instant createdFrom(KnowledgeQuery query) {
        return query.createdFrom() == null ? EARLIEST_CREATED_AT : query.createdFrom();
    }

    private static Instant createdTo(KnowledgeQuery query) {
        return query.createdTo() == null ? LATEST_CREATED_AT : query.createdTo();
    }
}
