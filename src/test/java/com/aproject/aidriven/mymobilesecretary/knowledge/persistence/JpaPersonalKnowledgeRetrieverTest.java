package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeQuery;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeSourceType;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class JpaPersonalKnowledgeRetrieverTest {

    private final UserKnowledgeFactRepository facts = mock(UserKnowledgeFactRepository.class);
    private final ObjectAnnotationRepository annotations = mock(ObjectAnnotationRepository.class);
    private final JpaPersonalKnowledgeRetriever retriever =
            new JpaPersonalKnowledgeRetriever(facts, annotations);

    @Test
    void blankQueryReturnsEmptyWithoutScanningRepositories() {
        WorkspaceContext context = context();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            assertThat(retriever.retrieve(query(context, " \t"))).isEmpty();
        }

        verifyNoInteractions(facts, annotations);
    }

    @Test
    void rejectsAQueryForAnotherActorBeforeRepositoryAccess() {
        WorkspaceContext context = context();
        KnowledgeQuery foreign = new KnowledgeQuery(
                context.workspaceId(), UUID.randomUUID(), "manual", Set.of(), Set.of(), 5,
                true, null, null, Map.of());

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            assertThatThrownBy(() -> retriever.retrieve(foreign))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("current actor scope");
        }
        verifyNoInteractions(facts, annotations);
    }

    @Test
    void passesWorkspaceAndActorPredicatesAndDoesNotInventARelevanceScore() {
        WorkspaceContext context = context();
        UserKnowledgeFact fact = mock(UserKnowledgeFact.class);
        Instant at = Instant.parse("2026-07-20T00:00:00Z");
        when(fact.getId()).thenReturn(7L);
        when(fact.getCategory()).thenReturn(UserKnowledgeFact.Category.RELATIONSHIP);
        when(fact.getSubject()).thenReturn("媽媽");
        when(fact.getDetail()).thenReturn("使用者明確教過的關係");
        when(fact.getCreatedAt()).thenReturn(at);
        when(fact.getUpdatedAt()).thenReturn(at);
        when(fact.getWorkspaceId()).thenReturn(context.workspaceId());
        when(fact.getCreatedByUserId()).thenReturn(context.actorId());
        when(facts.searchBySubjectPrefix(
                eq(context.workspaceId()), eq(context.actorId()), eq("媽媽"),
                eq(JpaPersonalKnowledgeRetriever.EARLIEST_CREATED_AT),
                eq(JpaPersonalKnowledgeRetriever.LATEST_CREATED_AT),
                eq(PageRequest.of(0, 5))))
                .thenReturn(List.of(fact));

        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            assertThat(retriever.retrieve(new KnowledgeQuery(
                    context.workspaceId(), context.actorId(), "媽媽", Set.of(),
                    Set.of(KnowledgeSourceType.USER_KNOWLEDGE_FACT), 5,
                    false, null, null, Map.of())))
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.content()).isEqualTo("使用者明確教過的關係");
                        assertThat(item.relevanceScore()).isNull();
                        assertThat(item.sourceVersion()).isNull();
                    });
        }
        verifyNoInteractions(annotations);
    }

    private static KnowledgeQuery query(WorkspaceContext context, String text) {
        return new KnowledgeQuery(
                context.workspaceId(), context.actorId(), text, Set.of(), Set.of(), 5,
                false, null, null, Map.of());
    }

    private static WorkspaceContext context() {
        return new WorkspaceContext(
                UUID.randomUUID(), UUID.randomUUID(), WorkspaceChannel.TEST);
    }
}
