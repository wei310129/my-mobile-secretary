package com.aproject.aidriven.mymobilesecretary.knowledge.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.domain.LegacyAccountIds;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeCategory;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeQuery;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.KnowledgeSourceType;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.retrieval.PersonalKnowledgeRetriever;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class JpaPersonalKnowledgeRetrieverIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-20T08:00:00Z");
    private static final WorkspaceContext OWNER = new WorkspaceContext(
            LegacyAccountIds.USER_ID, LegacyAccountIds.WORKSPACE_ID, WorkspaceChannel.TEST);

    @Autowired private UserKnowledgeService knowledgeService;
    @Autowired private ObjectAnnotationRepository annotationRepository;
    @Autowired private PersonalKnowledgeRetriever retriever;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void filtersWorkspaceOwnerAndCategoryInsideTheDatabaseQuery() {
        WorkspaceContext peer = createPeerInLegacyWorkspace();
        WorkspaceContext otherWorkspace = createActorWorkspace("Other");
        in(OWNER, () -> knowledgeService.remember(
                UserKnowledgeFact.Category.RELATIONSHIP, "媽媽", "owner relationship"));
        in(OWNER, () -> knowledgeService.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE, "媽媽入口", "owner place guidance"));
        in(peer, () -> knowledgeService.remember(
                UserKnowledgeFact.Category.RELATIONSHIP, "媽媽", "peer private relationship"));
        in(otherWorkspace, () -> knowledgeService.remember(
                UserKnowledgeFact.Category.RELATIONSHIP, "媽媽", "other workspace relationship"));

        var evidence = in(OWNER, () -> retriever.retrieve(new KnowledgeQuery(
                OWNER.workspaceId(), OWNER.actorId(), "媽媽",
                Set.of(KnowledgeCategory.RELATIONSHIP),
                Set.of(KnowledgeSourceType.USER_KNOWLEDGE_FACT), 10,
                true, null, null, Map.of())));

        assertThat(evidence).singleElement()
                .satisfies(item -> {
                    assertThat(item.content()).isEqualTo("owner relationship");
                    assertThat(item.workspaceId()).isEqualTo(OWNER.workspaceId());
                    assertThat(item.ownerUserId()).isEqualTo(OWNER.actorId());
                });
    }

    @Test
    void normalizedSubjectFindsFactsAndActiveFreeTextAnnotations() {
        in(OWNER, () -> knowledgeService.remember(
                UserKnowledgeFact.Category.PRODUCT_USAGE,
                "青葉・水泥漆", "客廳牆面使用"));
        in(OWNER, () -> annotationRepository.save(ObjectAnnotation.create(
                ObjectAnnotation.TargetType.ITEM, 7L,
                "洗衣機 E3", "排水異常時的本人註記", NOW)));

        var fact = in(OWNER, () -> retriever.retrieve(query("青葉 水泥漆", 10)));
        var annotation = in(OWNER, () -> retriever.retrieve(query("洗衣機-E3", 10)));

        assertThat(fact).extracting(item -> item.title())
                .containsExactly("青葉・水泥漆");
        assertThat(annotation).singleElement().satisfies(item -> {
            assertThat(item.sourceType()).isEqualTo(KnowledgeSourceType.OBJECT_ANNOTATION);
            assertThat(item.relevanceScore()).isNull();
            assertThat(item.sourceVersion()).isNull();
            assertThat(item.structured()).isFalse();
        });
    }

    @Test
    void capsResultsAndReturnsEmptyWhenNothingMatches() {
        for (int index = 0; index < 25; index++) {
            int current = index;
            in(OWNER, () -> knowledgeService.remember(
                    UserKnowledgeFact.Category.INTERPRETATION_PREFERENCE,
                    "手冊項目" + current, "detail " + current));
        }

        var capped = in(OWNER, () -> retriever.retrieve(query("手冊項目", 500)));
        var missing = in(OWNER, () -> retriever.retrieve(query("不存在的知識", 10)));

        assertThat(capped).hasSize(KnowledgeQuery.MAX_LIMIT)
                .allSatisfy(item -> assertThat(item.relevanceScore()).isNull());
        assertThat(missing).isEmpty();
    }

    private static KnowledgeQuery query(String text, int limit) {
        return new KnowledgeQuery(
                OWNER.workspaceId(), OWNER.actorId(), text, Set.of(), Set.of(), limit,
                false, null, null, Map.of());
    }

    private WorkspaceContext createPeerInLegacyWorkspace() {
        UUID actorId = UUID.randomUUID();
        insertUser(actorId, "Peer");
        jdbcTemplate.update("""
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), LegacyAccountIds.WORKSPACE_ID, actorId,
                LegacyAccountIds.USER_ID);
        return new WorkspaceContext(
                actorId, LegacyAccountIds.WORKSPACE_ID, WorkspaceChannel.TEST);
    }

    private WorkspaceContext createActorWorkspace(String label) {
        UUID actorId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        insertUser(actorId, label);
        jdbcTemplate.update("""
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, label, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'OWNER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), workspaceId, actorId, actorId);
        return new WorkspaceContext(actorId, workspaceId, WorkspaceChannel.TEST);
    }

    private void insertUser(UUID actorId, String label) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId, label);
    }

    private static <T> T in(WorkspaceContext context, Supplier<T> action) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return action.get();
        }
    }
}
