package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Adjustability;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.Criticality;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarActivityRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarSelectedScopeShareIntegrationTest extends IntegrationTestBase {

    private static final String RUNTIME_ROLE =
            "mms_calendar_selected_scope_runtime";
    private static final Instant NOW = Instant.parse("2026-07-25T09:00:00Z");

    @Autowired private CalendarShareService shares;
    @Autowired
    private CalendarAuthoritativeCapabilityService authoritativeCapabilities;
    @Autowired
    private CalendarAuthoritativeMutationService authoritativeMutations;
    @Autowired private CalendarEditorMutationService editorMutations;
    @Autowired private CalendarSelectedScopeQueryService selectedQuery;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarActivityRepository activities;
    @Autowired private CalendarTimeNodeRepository nodes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    void grantRuntimeRole() {
        jdbc.execute(
                """
                DO $$
                BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles
                        WHERE rolname =
                            'mms_calendar_selected_scope_runtime') THEN
                        CREATE ROLE mms_calendar_selected_scope_runtime
                            NOLOGIN NOSUPERUSER NOBYPASSRLS;
                    END IF;
                END $$
                """);
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE"
                        + " ON ALL TABLES IN SCHEMA public TO "
                        + RUNTIME_ROLE);
    }

    @Test
    void selectedActivityCapturesCurrentNodesWithoutFutureExpansion() {
        Fixture fixture = fixture();
        UUID activityId = inContext(fixture.ownerContext(), () -> {
            CalendarActivityEntity activity = activities.saveAndFlush(
                    CalendarActivityEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            "登船",
                            CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                            NOW));
            nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    activity.getId(),
                    CalendarTimeNode.absolute("boarding", "登船", NOW),
                    NOW));
            return activity.getId();
        });

        CalendarShareView share = inContext(fixture.ownerContext(), () -> shares.grantViewer(
                "selected-activity",
                fixture.planId(),
                fixture.recipient(),
                1,
                CalendarShareScope.selectedActivities(List.of(activityId))));
        long outboxBeforeNewChild = count("calendar_share_outbox");
        inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                CalendarTimeNodeEntity.create(
                        UUID.randomUUID(),
                        fixture.planId(),
                        activityId,
                        CalendarTimeNode.absolute(
                                "late-child", "後來新增", NOW.plusSeconds(600)),
                        NOW)));

        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .containsExactly("登船");
        assertThat(scopeItemCount("NODE")).isEqualTo(1L);
        assertThat(count("calendar_share_outbox"))
                .isEqualTo(outboxBeforeNewChild);
        assertThat(jdbc.queryForList(
                        """
                        SELECT event_type || ':' || share_id::text
                        FROM calendar_share_outbox
                        """,
                        String.class))
                .containsExactly("SHARE_CREATED:" + share.id());
        CalendarSelectedScopeView view = runtime(
                fixture.recipientContext(),
                () -> selectedQuery.get(fixture.planId()));
        assertThat(view.activities())
                .extracting(CalendarSelectedScopeView.ActivityContext::title)
                .containsExactly("登船");
        assertThat(view.nodes())
                .extracting(CalendarSelectedScopeView.NodeTarget::label)
                .containsExactly("登船");
    }

    @Test
    void ownerCanPreviewAndReviseSelectedActivitySnapshot() {
        Fixture fixture = fixture();
        UUID activityId = inContext(fixture.ownerContext(), () -> {
            CalendarActivityEntity activity = activities.saveAndFlush(
                    CalendarActivityEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            "可擴充活動",
                            CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                            NOW));
            nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    activity.getId(),
                    CalendarTimeNode.absolute("initial", "原節點", NOW),
                    NOW));
            return activity.getId();
        });
        CalendarShareScope scope =
                CalendarShareScope.selectedActivities(List.of(activityId));
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "expand-create",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        scope));
        inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                CalendarTimeNodeEntity.create(
                        UUID.randomUUID(),
                        fixture.planId(),
                        activityId,
                        CalendarTimeNode.absolute(
                                "expanded", "擴充節點", NOW.plusSeconds(600)),
                        NOW)));
        CalendarShareScopePreview preview = inContext(
                fixture.ownerContext(),
                () -> shares.previewViewerScope(
                        fixture.planId(), 1, scope));

        CalendarShareView revised = inContext(
                fixture.ownerContext(),
                () -> shares.reviseViewerScope(
                        "expand-revise", share.id(), 1, preview.digest()));
        CalendarShareView replay = inContext(
                fixture.ownerContext(),
                () -> shares.reviseViewerScope(
                        "expand-revise", share.id(), 1, preview.digest()));

        assertThat(revised.id()).isEqualTo(share.id());
        assertThat(replay.id()).isEqualTo(share.id());
        assertThat(runtime(
                                fixture.recipientContext(),
                                () -> selectedQuery.get(fixture.planId()))
                        .nodes())
                .extracting(CalendarSelectedScopeView.NodeTarget::label)
                .containsExactlyInAnyOrder("原節點", "擴充節點");
        assertThat(jdbc.queryForMap(
                        """
                        SELECT share_revision, scope_revision
                        FROM calendar_share WHERE id = ?
                        """,
                        share.id()))
                .containsEntry("share_revision", 1L)
                .containsEntry("scope_revision", 2L);
        assertThat(count("calendar_share_scope_snapshot")).isEqualTo(2L);
        assertThat(jdbc.queryForList(
                        """
                        SELECT event_type
                        FROM calendar_share_outbox
                        ORDER BY created_at, event_type
                        """,
                        String.class))
                .containsExactly("SHARE_CREATED", "SCOPE_REVISED");
    }

    @Test
    void stalePreviewCannotRevealAChildAddedAfterPreview() {
        Fixture fixture = fixture();
        UUID activityId = inContext(fixture.ownerContext(), () -> {
            CalendarActivityEntity activity = activities.saveAndFlush(
                    CalendarActivityEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            "預覽活動",
                            CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                            NOW));
            nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    activity.getId(),
                    CalendarTimeNode.absolute("before", "預覽前", NOW),
                    NOW));
            return activity.getId();
        });
        CalendarShareScope scope =
                CalendarShareScope.selectedActivities(List.of(activityId));
        CalendarShareScopePreview preview = inContext(
                fixture.ownerContext(),
                () -> shares.previewViewerScope(
                        fixture.planId(), 1, scope));
        inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                CalendarTimeNodeEntity.create(
                        UUID.randomUUID(),
                        fixture.planId(),
                        activityId,
                        CalendarTimeNode.absolute(
                                "after", "預覽後", NOW.plusSeconds(60)),
                        NOW)));

        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> shares.grantViewer(
                                "stale-preview",
                                fixture.planId(),
                                fixture.recipient(),
                                1,
                                scope,
                                preview.digest())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("preview again");
        assertThat(count("calendar_share")).isZero();
        assertThat(count("calendar_share_outbox")).isZero();
    }

    @Test
    void sealedSnapshotRejectsLateItemInsertion() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute("sealed", "封存節點", NOW),
                                NOW))
                .getId());
        inContext(fixture.ownerContext(), () -> shares.grantViewer(
                "sealed-scope",
                fixture.planId(),
                fixture.recipient(),
                1,
                CalendarShareScope.selectedNodes(List.of(nodeId))));

        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO calendar_share_scope_item (
                            id, snapshot_id, share_id, plan_id,
                            scope_revision, target_kind, activity_id,
                            node_id, target_version, context_title,
                            dependency_minimum, dependency_resolved_time,
                            dependency_revision, grantee_user_id, created_at,
                            workspace_id, created_by_user_id)
                        SELECT gen_random_uuid(), snapshot_id, share_id,
                               plan_id, scope_revision, target_kind,
                               activity_id, node_id, target_version,
                               context_title, dependency_minimum,
                               dependency_resolved_time,
                               dependency_revision, grantee_user_id,
                               created_at, workspace_id, created_by_user_id
                        FROM calendar_share_scope_item
                        LIMIT 1
                        """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("snapshot is sealed");
    }

    @Test
    void v85DatabaseContractUsesForcedRlsDeferredFksAndGuards() {
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_class
                        WHERE oid IN (
                            'calendar_share_scope_snapshot'::regclass,
                            'calendar_share_scope_item'::regclass,
                            'calendar_share_request_receipt'::regclass)
                          AND relrowsecurity AND relforcerowsecurity
                        """,
                        Long.class))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_constraint
                        WHERE conname IN (
                            'fk_calendar_share_current_scope_snapshot',
                            'fk_calendar_share_scope_snapshot_share',
                            'fk_calendar_share_scope_item_snapshot')
                          AND condeferrable AND condeferred
                        """,
                        Long.class))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM pg_trigger
                        WHERE tgname IN (
                            'trg_calendar_share_scope_snapshot_immutable',
                            'trg_calendar_share_scope_item_insert_guard',
                            'trg_calendar_share_scope_item_immutable',
                            'trg_calendar_share_scope_complete')
                          AND NOT tgisinternal
                        """,
                        Long.class))
                .isEqualTo(4L);
    }

    @Test
    void shareAndSnapshotScopeModesCannotDiverge() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute("mode", "模式節點", NOW),
                                NOW))
                .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "mode-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));

        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> {
                            new TransactionTemplate(transactions)
                                    .executeWithoutResult(status -> {
                                        jdbc.update(
                                                """
                                                UPDATE calendar_share
                                                SET scope_mode =
                                                    'SELECTED_ACTIVITIES'
                                                WHERE id = ?
                                                """,
                                                share.id());
                                        jdbc.execute(
                                                "SET CONSTRAINTS ALL IMMEDIATE");
                                    });
                            return null;
                        }))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void selectedRelativeNodeStoresOnlyOneLayerMinimumDependency() {
        Fixture fixture = fixture();
        UUID childId = inContext(fixture.ownerContext(), () -> {
            CalendarTimeNodeEntity base = nodes.saveAndFlush(
                    CalendarTimeNodeEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            null,
                            CalendarTimeNode.absolute("departure", "離港", NOW),
                            NOW));
            CalendarTimeNodeEntity child = nodes.saveAndFlush(
                    CalendarTimeNodeEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            null,
                            CalendarTimeNode.relativeToNode(
                                    "boarding",
                                    "登船",
                                    "departure",
                                    Duration.ofMinutes(-30),
                                    Criticality.CRITICAL,
                                    Adjustability.LOCKED),
                            NOW.minus(Duration.ofMinutes(30)),
                            NOW));
            assertThat(base.getId()).isNotNull();
            return child.getId();
        });

        inContext(fixture.ownerContext(), () -> shares.grantViewer(
                "selected-relative",
                fixture.planId(),
                fixture.recipient(),
                1,
                CalendarShareScope.selectedNodes(List.of(childId))));

        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .containsExactly("登船");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_share_scope_item
                        WHERE target_kind = 'DEPENDENCY_MINIMUM'
                          AND dependency_resolved_time IS NOT NULL
                          AND dependency_revision = 1
                        """,
                        Long.class))
                .isEqualTo(1L);
        CalendarSelectedScopeView view = runtime(
                fixture.recipientContext(),
                () -> selectedQuery.get(fixture.planId()));
        CalendarSelectedScopeView.NodeTarget target =
                view.nodes().getFirst();
        assertThat(target.label()).isEqualTo("登船");
        assertThat(target.expressionKind()).isEqualTo("NODE_OFFSET");
        assertThat(target.offsetSeconds()).isEqualTo(-1800L);
        assertThat(view.dependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.dependentNodeId()).isEqualTo(target.id());
            assertThat(dependency.resolvedTime()).isEqualTo(NOW);
            assertThat(dependency.revision()).isEqualTo(1);
            assertThat(dependency.resolvedTime()
                            .plusSeconds(target.offsetSeconds()))
                    .isEqualTo(target.resolvedTime());
        });
    }

    @Test
    void selectedActivityPreviewsAndStoresExternalMinimumDependency() {
        Fixture fixture = fixture();
        UUID activityId = inContext(fixture.ownerContext(), () -> {
            nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    null,
                    CalendarTimeNode.absolute("plan-base", "計畫基準", NOW),
                    NOW));
            CalendarActivityEntity activity = activities.saveAndFlush(
                    CalendarActivityEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            "集合",
                            CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                            NOW));
            nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    activity.getId(),
                    CalendarTimeNode.relativeToNode(
                            "meeting",
                            "集合時間",
                            "plan-base",
                            Duration.ofMinutes(-20),
                            Criticality.NORMAL,
                            Adjustability.LOCKED),
                    NOW.minus(Duration.ofMinutes(20)),
                    NOW));
            return activity.getId();
        });
        CalendarShareScope scope =
                CalendarShareScope.selectedActivities(List.of(activityId));

        CalendarShareScopePreview preview = inContext(
                fixture.ownerContext(),
                () -> shares.previewViewerScope(fixture.planId(), 1, scope));
        assertThat(preview.visibleTargetCount()).isEqualTo(2);
        assertThat(preview.dependencyMinimums())
                .singleElement()
                .satisfies(dependency -> {
                    assertThat(dependency.resolvedTime()).isEqualTo(NOW);
                    assertThat(dependency.revision()).isEqualTo(1);
                });

        inContext(fixture.ownerContext(), () -> shares.grantViewer(
                "selected-activity-dependency",
                fixture.planId(),
                fixture.recipient(),
                1,
                scope));
        CalendarSelectedScopeView view = runtime(
                fixture.recipientContext(),
                () -> selectedQuery.get(fixture.planId()));
        assertThat(view.nodes())
                .extracting(CalendarSelectedScopeView.NodeTarget::label)
                .containsExactly("集合時間");
        assertThat(view.dependencies()).hasSize(1);
        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .containsExactly("集合時間");
    }

    @Test
    void selectedNodeReturnsMinimalParentContextAndHidesSibling() {
        Fixture fixture = fixture();
        TargetWithSibling target = inContext(fixture.ownerContext(), () -> {
            CalendarActivityEntity activity = activities.saveAndFlush(
                    CalendarActivityEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            "港口活動",
                            CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                            NOW));
            CalendarTimeNodeEntity selected = CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    activity.getId(),
                    CalendarTimeNode.absolute("boarding", "登船", NOW),
                    NOW);
            selected.reviseLocation(
                    new CalendarLocation("一號碼頭", 25.0, 121.0), 1, NOW);
            nodes.saveAndFlush(selected);
            CalendarTimeNodeEntity sibling = nodes.saveAndFlush(
                    CalendarTimeNodeEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            activity.getId(),
                            CalendarTimeNode.absolute(
                                    "private-sibling",
                                    "未分享集合",
                                    NOW.plusSeconds(300)),
                            NOW));
            return new TargetWithSibling(
                    activity.getId(), selected.getId(), sibling.getId());
        });

        inContext(fixture.ownerContext(), () -> shares.grantViewer(
                "selected-minimal-parent",
                fixture.planId(),
                fixture.recipient(),
                1,
                CalendarShareScope.selectedNodes(
                        List.of(target.selectedNodeId()))));

        CalendarSelectedScopeView view = runtime(
                fixture.recipientContext(),
                () -> selectedQuery.get(fixture.planId()));
        assertThat(view.planStatus()).isEqualTo("ACTIVE");
        assertThat(view.ownerDisplayName()).isEqualTo("selected owner");
        assertThat(view.activities()).singleElement().satisfies(activity -> {
            assertThat(activity.id()).isEqualTo(target.activityId());
            assertThat(activity.title()).isEqualTo("港口活動");
            assertThat(activity.explicitlySelected()).isFalse();
            assertThat(activity.safeHost()).isNull();
        });
        assertThat(view.nodes()).singleElement().satisfies(node -> {
            assertThat(node.id()).isEqualTo(target.selectedNodeId());
            assertThat(node.label()).isEqualTo("登船");
            assertThat(node.locationLabel()).isEqualTo("一號碼頭");
            assertThat(node.criticality()).isEqualTo("NORMAL");
            assertThat(node.adjustability()).isEqualTo("LOCKED");
        });
        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .containsExactly("登船");
    }

    @Test
    void multiLayerRelativeDependencyRollsBackTheWholeGrant() {
        Fixture fixture = fixture();
        UUID childId = inContext(fixture.ownerContext(), () -> {
            nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    null,
                    CalendarTimeNode.absolute("base", "底層", NOW),
                    NOW));
            nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                    UUID.randomUUID(),
                    fixture.planId(),
                    null,
                    CalendarTimeNode.relativeToNode(
                            "middle",
                            "中層",
                            "base",
                            Duration.ofMinutes(-10),
                            Criticality.NORMAL,
                            Adjustability.LOCKED),
                    NOW.minus(Duration.ofMinutes(10)),
                    NOW));
            return nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                            UUID.randomUUID(),
                            fixture.planId(),
                            null,
                            CalendarTimeNode.relativeToNode(
                                    "child",
                                    "上層",
                                    "middle",
                                    Duration.ofMinutes(-10),
                                    Criticality.NORMAL,
                                    Adjustability.LOCKED),
                            NOW.minus(Duration.ofMinutes(20)),
                            NOW))
                    .getId();
        });

        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> shares.grantViewer(
                                "unsafe-dependency",
                                fixture.planId(),
                                fixture.recipient(),
                                1,
                                CalendarShareScope.selectedNodes(
                                        List.of(childId)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("minimal dependency");
        assertThat(count("calendar_share")).isZero();
        assertThat(count("calendar_share_scope_snapshot")).isZero();
        assertThat(count("calendar_share_outbox")).isZero();
    }

    @Test
    void semanticReplayCreatesOneShareAndOneOutboxAcrossDifferentKeys() {
        Fixture fixture = fixture();

        CalendarShareView first = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "semantic-one",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        CalendarShareView replay = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "semantic-two",
                        fixture.planId(),
                        fixture.recipient(),
                        1));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(count("calendar_share")).isEqualTo(1L);
        assertThat(count("calendar_share_request_receipt")).isEqualTo(2L);
        assertThat(count("calendar_share_outbox")).isEqualTo(1L);
    }

    @Test
    void concurrentSemanticReplayConvergesOnOneGrantAndOneOutbox()
            throws Exception {
        Fixture fixture = fixture();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return inContext(fixture.ownerContext(), () ->
                        shares.createViewerShare(
                                "concurrent-semantic-one",
                                fixture.planId(),
                                fixture.recipient(),
                                1));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return inContext(fixture.ownerContext(), () ->
                        shares.createViewerShare(
                                "concurrent-semantic-two",
                                fixture.planId(),
                                fixture.recipient(),
                                1));
            });
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(30, TimeUnit.SECONDS).id());
        }
        assertThat(count("calendar_share")).isEqualTo(1L);
        assertThat(count("calendar_share_request_receipt")).isEqualTo(2L);
        assertThat(count("calendar_share_outbox")).isEqualTo(1L);
    }

    @Test
    void concurrentSelectedScopeReplayCreatesOneSnapshot() throws Exception {
        Fixture fixture = fixture();
        UUID nodeId = inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "concurrent-selected",
                                        "並行節點",
                                        NOW),
                                NOW))
                .getId());
        CalendarShareScope scope =
                CalendarShareScope.selectedNodes(List.of(nodeId));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return inContext(fixture.ownerContext(), () ->
                        shares.grantViewer(
                                "concurrent-selected-one",
                                fixture.planId(),
                                fixture.recipient(),
                                1,
                                scope));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return inContext(fixture.ownerContext(), () ->
                        shares.grantViewer(
                                "concurrent-selected-two",
                                fixture.planId(),
                                fixture.recipient(),
                                1,
                                scope));
            });
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(30, TimeUnit.SECONDS).id());
        }
        assertThat(count("calendar_share")).isEqualTo(1L);
        assertThat(count("calendar_share_scope_snapshot")).isEqualTo(1L);
        assertThat(count("calendar_share_scope_item")).isEqualTo(1L);
        assertThat(count("calendar_share_request_receipt")).isEqualTo(2L);
        assertThat(count("calendar_share_outbox")).isEqualTo(1L);
    }

    @Test
    void sameRequestKeyWithDifferentScopeFailsClosed() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute("only", "單一", NOW),
                                NOW))
                .getId());
        inContext(fixture.ownerContext(), () -> shares.createViewerShare(
                "same-request",
                fixture.planId(),
                fixture.recipient(),
                1));

        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> shares.grantViewer(
                                "same-request",
                                fixture.planId(),
                                fixture.recipient(),
                                1,
                                CalendarShareScope.selectedNodes(
                                        List.of(nodeId)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("request key");
    }

    @Test
    void revokingOneAdditiveGrantKeepsTheOtherGrantVisible() {
        Fixture fixture = fixture();
        List<UUID> nodeIds = inContext(fixture.ownerContext(), () -> List.of(
                nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute("first", "第一站", NOW),
                                NOW))
                        .getId(),
                nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "second", "第二站", NOW.plusSeconds(600)),
                                NOW))
                        .getId()));
        CalendarShareView first = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "additive-first",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(
                                List.of(nodeIds.getFirst()))));
        CalendarShareView second = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "additive-second",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(
                                List.of(nodeIds.getLast()))));

        assertThat(runtime(
                                fixture.recipientContext(),
                                () -> selectedQuery.get(fixture.planId()))
                        .nodes())
                .extracting(CalendarSelectedScopeView.NodeTarget::label)
                .containsExactlyInAnyOrder("第一站", "第二站");

        inContext(
                fixture.ownerContext(),
                () -> shares.revoke("revoke-first", first.id(), 1));

        CalendarSelectedScopeView remaining = runtime(
                fixture.recipientContext(),
                () -> selectedQuery.get(fixture.planId()));
        assertThat(remaining.nodes())
                .extracting(CalendarSelectedScopeView.NodeTarget::label)
                .containsExactly("第二站");
        assertThat(remaining.grantSources()).containsExactly(second.id());
    }

    @Test
    void overlappingWholePlanGrantKeepsNodeVisibleAfterSelectedRevoke() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute("overlap", "重疊節點", NOW),
                                NOW))
                .getId());
        inContext(fixture.ownerContext(), () -> shares.createViewerShare(
                "overlap-whole",
                fixture.planId(),
                fixture.recipient(),
                1));
        CalendarShareView selected = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "overlap-selected",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));

        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .containsExactly("重疊節點");
        inContext(
                fixture.ownerContext(),
                () -> shares.revoke(
                        "overlap-revoke-selected", selected.id(), 1));
        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .containsExactly("重疊節點");
    }

    @Test
    void selectedScopeRlsExcludesPeerSystemCrossWorkspaceAndFormerMember() {
        Fixture fixture = fixture();
        UUID peer = UUID.randomUUID();
        seedUser(peer, "selected peer");
        addMember(
                fixture.ownerContext().workspaceId(),
                peer,
                fixture.ownerContext().actorId(),
                "MEMBER");
        UUID outsider = UUID.randomUUID();
        UUID otherWorkspace = UUID.randomUUID();
        seedUser(outsider, "selected outsider");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'selected other', 'PERSONAL', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                otherWorkspace,
                outsider);
        UUID nodeId = inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute("private", "指定節點", NOW),
                                NOW))
                .getId());
        inContext(fixture.ownerContext(), () -> shares.grantViewer(
                "rls-selected",
                fixture.planId(),
                fixture.recipient(),
                1,
                CalendarShareScope.selectedNodes(List.of(nodeId))));

        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .containsExactly("指定節點");
        assertThat(runtime(
                        context(peer, fixture.ownerContext().workspaceId()),
                        this::visibleNodeLabels))
                .isEmpty();
        assertThat(runtime(
                        context(outsider, otherWorkspace),
                        this::visibleNodeLabels))
                .isEmpty();
        assertThat(runtime(WorkspaceContext.system(), this::visibleNodeLabels))
                .isEmpty();

        jdbc.update(
                "UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?",
                fixture.recipient());
        assertThat(runtime(fixture.recipientContext(), this::visibleNodeLabels))
                .isEmpty();
    }

    @Test
    void roleChangeAdvancesOnlyShareRevisionAndReplaysOneTransition() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(fixture.ownerContext(), () -> nodes.saveAndFlush(
                        CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "role-target", "角色目標", NOW),
                                NOW))
                .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "role-selected",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));

        CalendarShareView changed = inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "role-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarShareView replay = inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "role-editor-retry",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));

        assertThat(changed.revision()).isEqualTo(2);
        assertThat(replay.revision()).isEqualTo(2);
        assertThat(jdbc.queryForMap(
                        """
                        SELECT permission, share_revision, scope_revision,
                               current_scope_snapshot_id
                        FROM calendar_share WHERE id = ?
                        """,
                        share.id()))
                .containsEntry("permission", "EDITOR")
                .containsEntry("share_revision", 2L)
                .containsEntry("scope_revision", 1L);
        assertThat(count("calendar_share_scope_snapshot")).isEqualTo(1);
        assertThat(count("calendar_share_role_audit")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'ROLE_CHANGED'
                        """,
                        Long.class))
                .isEqualTo(1);
    }

    @Test
    void roleChangeRejectsStaleRevisionWithoutPartialAuditOrOutbox() {
        Fixture fixture = fixture();
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "role-stale-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));

        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> shares.changeRole(new CalendarShareRoleChange(
                                "role-stale",
                                share.id(),
                                CalendarSharePermission.EDITOR,
                                2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("changed");
        assertThat(count("calendar_share_role_audit")).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'ROLE_CHANGED'
                        """,
                        Long.class))
                .isZero();
    }

    @Test
    void concurrentSameKeyRoleChangeConvergesOnOneTransition()
            throws Exception {
        Fixture fixture = fixture();
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "role-concurrent-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        CalendarShareRoleChange change = new CalendarShareRoleChange(
                "role-concurrent",
                share.id(),
                CalendarSharePermission.EDITOR,
                1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return inContext(
                        fixture.ownerContext(),
                        () -> shares.changeRole(change));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await(30, TimeUnit.SECONDS);
                return inContext(
                        fixture.ownerContext(),
                        () -> shares.changeRole(change));
            });
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isEqualTo(second.get(30, TimeUnit.SECONDS));
        }
        assertThat(count("calendar_share_role_audit")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'ROLE_CHANGED'
                        """,
                        Long.class))
                .isEqualTo(1);
    }

    @Test
    void editorDowngradeRevokesActiveAuthoritativeCapabilities() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "role-capability-node",
                                        "權限節點",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "role-capability-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "role-capability-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        UUID planCapabilityId = inContext(
                        fixture.ownerContext(),
                        () -> authoritativeCapabilities.grant(
                                new CalendarAuthoritativeCapabilityGrant(
                                        "role-capability-plan",
                                        share.id(),
                                        CalendarAuthoritativeCapabilityGrant
                                                .Scope.PLAN,
                                        null,
                                        2)))
                .id();
        UUID nodeCapabilityId = inContext(
                        fixture.ownerContext(),
                        () -> authoritativeCapabilities.grant(
                                new CalendarAuthoritativeCapabilityGrant(
                                        "role-capability-node",
                                        share.id(),
                                        CalendarAuthoritativeCapabilityGrant
                                                .Scope.NODE,
                                        nodeId,
                                        2)))
                .id();
        runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeNodeLabel(
                        new CalendarEditorNodeLabelChange(
                                "role-capability-editor-mutation",
                                share.id(),
                                nodeId,
                                "降級前一般編輯",
                                1)));
        runtime(
                fixture.recipientContext(),
                () -> authoritativeMutations.reviseAbsoluteTime(
                        new CalendarAuthoritativeTimeChange(
                                "role-capability-authoritative-mutation",
                                nodeCapabilityId,
                                nodeId,
                                NOW.plusSeconds(600),
                                2,
                                "降級前權威更新",
                                "test")));
        CalendarShareRoleChange downgrade = new CalendarShareRoleChange(
                "role-capability-viewer",
                share.id(),
                CalendarSharePermission.VIEWER,
                2);

        CalendarShareView changed = inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(downgrade));
        CalendarShareView replay = inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(downgrade));

        assertThat(replay).isEqualTo(changed);
        assertThat(jdbc.queryForList(
                        """
                        SELECT id, status, capability_revision,
                               revoked_at IS NOT NULL AS revoked
                        FROM calendar_authoritative_editor_capability
                        WHERE share_id = ?
                        ORDER BY id
                        """,
                        share.id()))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("status", "REVOKED")
                        .containsEntry("capability_revision", 2L)
                        .containsEntry("revoked", true));
        List<String> payloads = jdbc.queryForList(
                """
                SELECT payload_text
                FROM calendar_share_outbox
                WHERE event_type =
                    'AUTHORITATIVE_CAPABILITY_REVOKED'
                  AND share_id = ?
                ORDER BY payload_text
                """,
                String.class,
                share.id());
        assertThat(payloads)
                .hasSize(2)
                .anySatisfy(payload -> assertThat(payload)
                        .contains("capabilityId=" + planCapabilityId)
                        .contains("capabilityRevision=2")
                        .contains("cause=ROLE_DOWNGRADED"))
                .anySatisfy(payload -> assertThat(payload)
                        .contains("capabilityId=" + nodeCapabilityId)
                        .contains("capabilityRevision=2")
                        .contains("cause=ROLE_DOWNGRADED"));
        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT
                                  (SELECT count(*)
                                   FROM calendar_editor_mutation_audit)
                                  +
                                  (SELECT count(*)
                                   FROM calendar_authoritative_mutation_audit)
                                """,
                                Long.class)))
                .isZero();
        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM calendar_share_outbox
                                WHERE event_type IN (
                                    'EDITOR_MUTATION',
                                    'AUTHORITATIVE_MUTATION')
                                """,
                                Long.class)))
                .isZero();
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> authoritativeMutations.reviseAbsoluteTime(
                                new CalendarAuthoritativeTimeChange(
                                        "role-capability-after-downgrade",
                                        nodeCapabilityId,
                                        nodeId,
                                        NOW.plusSeconds(1200),
                                        3,
                                        "降級後不得更新",
                                        "test"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void fullShareRevokeCascadesEachCapabilityAndImmediatelyHidesMutationEvidence() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "full-revoke-node",
                                        "撤銷前節點",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "full-revoke-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "full-revoke-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        UUID planCapabilityId = inContext(
                        fixture.ownerContext(),
                        () -> authoritativeCapabilities.grant(
                                new CalendarAuthoritativeCapabilityGrant(
                                        "full-revoke-plan-capability",
                                        share.id(),
                                        CalendarAuthoritativeCapabilityGrant
                                                .Scope.PLAN,
                                        null,
                                        2)))
                .id();
        UUID nodeCapabilityId = inContext(
                        fixture.ownerContext(),
                        () -> authoritativeCapabilities.grant(
                                new CalendarAuthoritativeCapabilityGrant(
                                        "full-revoke-node-capability",
                                        share.id(),
                                        CalendarAuthoritativeCapabilityGrant
                                                .Scope.NODE,
                                        nodeId,
                                        2)))
                .id();
        runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeNodeLabel(
                        new CalendarEditorNodeLabelChange(
                                "full-revoke-editor-mutation",
                                share.id(),
                                nodeId,
                                "撤銷前一般編輯",
                                1)));
        runtime(
                fixture.recipientContext(),
                () -> authoritativeMutations.reviseAbsoluteTime(
                        new CalendarAuthoritativeTimeChange(
                                "full-revoke-authoritative-mutation",
                                planCapabilityId,
                                nodeId,
                                NOW.plusSeconds(600),
                                2,
                                "撤銷前權威更新",
                                "test")));

        inContext(
                fixture.ownerContext(),
                () -> shares.revoke(
                        "full-revoke-operation", share.id(), 2));

        assertThat(jdbc.queryForList(
                        """
                        SELECT id, status, capability_revision,
                               revoked_at IS NOT NULL AS revoked
                        FROM calendar_authoritative_editor_capability
                        WHERE share_id = ?
                        ORDER BY id
                        """,
                        share.id()))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row)
                        .containsEntry("status", "REVOKED")
                        .containsEntry("capability_revision", 2L)
                        .containsEntry("revoked", true));
        List<String> payloads = jdbc.queryForList(
                """
                SELECT payload_text
                FROM calendar_share_outbox
                WHERE event_type =
                    'AUTHORITATIVE_CAPABILITY_REVOKED'
                  AND share_id = ?
                ORDER BY payload_text
                """,
                String.class,
                share.id());
        assertThat(payloads)
                .hasSize(2)
                .anySatisfy(payload -> assertThat(payload)
                        .contains("capabilityId=" + planCapabilityId)
                        .contains("capabilityRevision=2")
                        .contains("cause=SHARE_REVOKED"))
                .anySatisfy(payload -> assertThat(payload)
                        .contains("capabilityId=" + nodeCapabilityId)
                        .contains("capabilityRevision=2")
                        .contains("cause=SHARE_REVOKED"));
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> editorMutations.changeNodeLabel(
                                new CalendarEditorNodeLabelChange(
                                        "full-revoke-editor-after",
                                        share.id(),
                                        nodeId,
                                        "不應成功",
                                        3))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> authoritativeMutations.reviseAbsoluteTime(
                                new CalendarAuthoritativeTimeChange(
                                        "full-revoke-authoritative-after",
                                        nodeCapabilityId,
                                        nodeId,
                                        NOW.plusSeconds(1200),
                                        3,
                                        "撤銷後不得更新",
                                        "test"))))
                .isInstanceOf(NotFoundException.class);
        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT
                                  (SELECT count(*)
                                   FROM calendar_editor_mutation_audit)
                                  +
                                  (SELECT count(*)
                                   FROM calendar_authoritative_mutation_audit)
                                """,
                                Long.class)))
                .isZero();
        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForObject(
                                """
                                SELECT count(*)
                                FROM calendar_share_outbox
                                WHERE event_type IN (
                                    'EDITOR_MUTATION',
                                    'AUTHORITATIVE_MUTATION')
                                """,
                                Long.class)))
                .isZero();
    }

    @Test
    void selectedRecipientCanReadCanceledNodeTombstone() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "selected-canceled-node",
                                        "已取消節點",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "selected-canceled-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "selected-canceled-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> authoritativeCapabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "selected-canceled-capability",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.NODE,
                                nodeId,
                                2)));

        runtime(
                fixture.recipientContext(),
                () -> authoritativeMutations.cancelNode(
                        new CalendarAuthoritativeCancellationChange(
                                "selected-canceled-mutation",
                                capability.id(),
                                nodeId,
                                1,
                                "行程取消",
                                "provider-update")));

        assertThat(runtime(
                        fixture.recipientContext(),
                        () -> jdbc.queryForMap(
                                """
                                SELECT cancellation_status, canceled_at
                                FROM calendar_time_node
                                WHERE id = ?
                                """,
                                nodeId)))
                .containsEntry("cancellation_status", "CANCELED")
                .satisfies(row ->
                        assertThat(row.get("canceled_at")).isNotNull());
    }

    @Test
    void generalEditorWithoutAuthoritativeCapabilityCannotMutateTimeLocationOrCancellation() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-authoritative-denied",
                                        "一般編輯者",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "editor-authoritative-denied-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "editor-authoritative-denied-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        UUID nonexistentCapability = UUID.randomUUID();

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> authoritativeMutations.reviseAbsoluteTime(
                                new CalendarAuthoritativeTimeChange(
                                        "editor-denied-time-api",
                                        nonexistentCapability,
                                        nodeId,
                                        NOW.plusSeconds(900),
                                        1,
                                        "一般編輯者不得改時間",
                                        "test"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> authoritativeMutations.reviseLocation(
                                new CalendarAuthoritativeLocationChange(
                                        "editor-denied-location-api",
                                        nonexistentCapability,
                                        nodeId,
                                        new CalendarLocation(
                                                "未授權地點",
                                                25.04,
                                                121.51),
                                        1,
                                        "一般編輯者不得改地點",
                                        "test"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> authoritativeMutations.cancelNode(
                                new CalendarAuthoritativeCancellationChange(
                                        "editor-denied-cancel-api",
                                        nonexistentCapability,
                                        nodeId,
                                        1,
                                        "一般編輯者不得取消",
                                        "test"))))
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> jdbc.update(
                                """
                                UPDATE calendar_time_node
                                SET absolute_time = ?,
                                    resolved_time = ?,
                                    revision = revision + 1,
                                    version = version + 1
                                WHERE id = ?
                                """,
                                NOW.plusSeconds(900),
                                NOW.plusSeconds(900),
                                nodeId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> jdbc.update(
                                """
                                UPDATE calendar_time_node
                                SET location_label = '未授權地點',
                                    latitude = 25.04,
                                    longitude = 121.51,
                                    revision = revision + 1,
                                    version = version + 1
                                WHERE id = ?
                                """,
                                nodeId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> jdbc.update(
                                """
                                UPDATE calendar_time_node
                                SET cancellation_status = 'CANCELED',
                                    canceled_at = CURRENT_TIMESTAMP,
                                    revision = revision + 1,
                                    version = version + 1
                                WHERE id = ?
                                """,
                                nodeId)))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForMap(
                        """
                        SELECT absolute_time, location_label,
                               cancellation_status, revision, version
                        FROM calendar_time_node WHERE id = ?
                        """,
                        nodeId))
                .containsEntry("cancellation_status", "ACTIVE")
                .containsEntry("revision", 1L)
                .containsEntry("version", 0L);
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'AUTHORITATIVE_MUTATION'
                        """,
                        Long.class))
                .isZero();
    }

    @Test
    void selectedNodeEditorMutationIsAuditedScopedAndIdempotent() {
        Fixture fixture = fixture();
        UUID selectedNode = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-selected",
                                        "編輯前",
                                        NOW),
                                NOW))
                        .getId());
        UUID siblingNode = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-sibling",
                                        "不可編輯",
                                        NOW.plusSeconds(60)),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "editor-selected-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(
                                List.of(selectedNode))));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "editor-selected-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarEditorNodeLabelChange change =
                new CalendarEditorNodeLabelChange(
                        "editor-selected-mutation",
                        share.id(),
                        selectedNode,
                        "編輯後",
                        1);

        CalendarEditorMutationResult result = runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeNodeLabel(change));
        CalendarEditorMutationResult replay = runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeNodeLabel(change));

        assertThat(replay).isEqualTo(result);
        assertThat(jdbc.queryForMap(
                        """
                        SELECT label, revision, version
                        FROM calendar_time_node WHERE id = ?
                        """,
                        selectedNode))
                .containsEntry("label", "編輯後")
                .containsEntry("revision", 2L)
                .containsEntry("version", 1L);
        assertThat(jdbc.queryForObject(
                        "SELECT label FROM calendar_time_node WHERE id = ?",
                        String.class,
                        siblingNode))
                .isEqualTo("不可編輯");
        assertThat(count("calendar_editor_mutation_audit")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'EDITOR_MUTATION'
                        """,
                        Long.class))
                .isEqualTo(1);
    }

    @Test
    void viewerAndOutOfScopeEditorCannotMutateNode() {
        Fixture fixture = fixture();
        UUID allowedNode = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-allowed",
                                        "允許目標",
                                        NOW),
                                NOW))
                        .getId());
        UUID deniedNode = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-denied",
                                        "範圍外",
                                        NOW.plusSeconds(60)),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "editor-denied-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(
                                List.of(allowedNode))));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> editorMutations.changeNodeLabel(
                                new CalendarEditorNodeLabelChange(
                                        "viewer-denied",
                                        share.id(),
                                        allowedNode,
                                        "不應成功",
                                        1))))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("shared plan");

        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "editor-denied-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> editorMutations.changeNodeLabel(
                                new CalendarEditorNodeLabelChange(
                                        "scope-denied",
                                        share.id(),
                                        deniedNode,
                                        "不應成功",
                                        1))))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("authorized node");
        assertThat(count("calendar_editor_mutation_audit")).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'EDITOR_MUTATION'
                        """,
                        Long.class))
                .isZero();
    }

    @Test
    void fakeEditorMutationMarkerCannotBypassAuditGuard() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-fake",
                                        "原始標籤",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "editor-fake-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "editor-fake-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> {
                            jdbc.queryForObject(
                                    """
                                    SELECT set_config(
                                        'app.calendar_editor_mutation_id',
                                        ?, true)
                                    """,
                                    String.class,
                                    UUID.randomUUID().toString());
                            return jdbc.update(
                                    """
                                    UPDATE calendar_time_node
                                    SET label = '偽造標籤',
                                        revision = revision + 1,
                                        version = version + 1,
                                        updated_at = GREATEST(
                                            updated_at,
                                            CURRENT_TIMESTAMP)
                                    WHERE id = ?
                                    """,
                                    nodeId);
                        }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("audit mismatch");
        assertThat(jdbc.queryForObject(
                        "SELECT label FROM calendar_time_node WHERE id = ?",
                        String.class,
                        nodeId))
                .isEqualTo("原始標籤");
        assertThat(count("calendar_editor_mutation_audit")).isZero();
    }

    @Test
    void forgedEditorAuditWithoutOutboxRollsBackAtCommit() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-forged-audit",
                                        "原始標籤",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "editor-forged-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "editor-forged-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> {
                            UUID mutationId = UUID.randomUUID();
                            jdbc.update(
                                    """
                                    INSERT INTO
                                      calendar_editor_mutation_audit (
                                        id, mutation_id, share_id,
                                        plan_id, node_id, activity_id,
                                        mutation_kind, before_value,
                                        after_value,
                                        previous_target_revision,
                                        current_target_revision,
                                        operation_request_hash,
                                        occurred_at, workspace_id,
                                        created_by_user_id,
                                        editor_user_id)
                                    SELECT gen_random_uuid(), ?, ?, plan_id,
                                           id, NULL, 'NODE_LABEL', label,
                                           '偽造標籤', revision,
                                           revision + 1, ?,
                                           CURRENT_TIMESTAMP,
                                           workspace_id,
                                           created_by_user_id, ?
                                    FROM calendar_time_node WHERE id = ?
                                    """,
                                    mutationId,
                                    share.id(),
                                    CalendarShareService.hash(
                                            "forged-editor|"
                                                    + mutationId),
                                    fixture.recipient(),
                                    nodeId);
                            jdbc.queryForObject(
                                    """
                                    SELECT set_config(
                                      'app.calendar_editor_mutation_id',
                                      ?, true)
                                    """,
                                    String.class,
                                    mutationId.toString());
                            return jdbc.update(
                                    """
                                    UPDATE calendar_time_node
                                    SET label = '偽造標籤',
                                        revision = revision + 1,
                                        version = version + 1,
                                        updated_at = GREATEST(
                                            updated_at,
                                            CURRENT_TIMESTAMP)
                                    WHERE id = ?
                                    """,
                                    nodeId);
                        }))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("durable outbox");
        assertThat(jdbc.queryForObject(
                        "SELECT label FROM calendar_time_node WHERE id = ?",
                        String.class,
                        nodeId))
                .isEqualTo("原始標籤");
        assertThat(count("calendar_editor_mutation_audit")).isZero();
    }

    @Test
    void matchingEditorAuditAndOutboxWithoutTargetUpdateRollsBackAtCommit() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "editor-missing-target-update",
                                        "仍是原始標籤",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "editor-missing-target-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "editor-missing-target-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> insertMatchingEditorAuditAndOutboxWithoutUpdate(
                                share.id(),
                                nodeId,
                                "偽造完成標籤",
                                "editor-missing-target")))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("exact target transition");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT label FROM calendar_time_node
                        WHERE id = ?
                        """,
                        String.class,
                        nodeId))
                .isEqualTo("仍是原始標籤");
        assertThat(count("calendar_editor_mutation_audit")).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'EDITOR_MUTATION'
                        """,
                        Long.class))
                .isZero();
    }

    @Test
    void selectedActivityEditorCanChangeTitleAndCategoryOnlyForSnapshotTarget() {
        Fixture fixture = fixture();
        UUID activityId = inContext(fixture.ownerContext(), () -> activities
                .saveAndFlush(CalendarActivityEntity.create(
                        UUID.randomUUID(),
                        fixture.planId(),
                        "活動原名",
                        CalendarPlacement.point(
                                NOW, ZoneId.of("Asia/Taipei")),
                        NOW))
                .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "activity-editor-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedActivities(
                                List.of(activityId))));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "activity-editor-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        UUID laterActivity = inContext(fixture.ownerContext(), () -> activities
                .saveAndFlush(CalendarActivityEntity.create(
                        UUID.randomUUID(),
                        fixture.planId(),
                        "後來活動",
                        CalendarPlacement.point(
                                NOW.plusSeconds(60),
                                ZoneId.of("Asia/Taipei")),
                        NOW))
                .getId());

        CalendarEditorActivityMutationResult titleResult = runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeActivity(
                        new CalendarEditorActivityChange(
                                "activity-title",
                                share.id(),
                                activityId,
                                CalendarEditorActivityChange.Field.TITLE,
                                "活動新名",
                                0)));
        CalendarEditorActivityMutationResult categoryResult = runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeActivity(
                        new CalendarEditorActivityChange(
                                "activity-category",
                                share.id(),
                                activityId,
                                CalendarEditorActivityChange.Field.CATEGORY,
                                "交通",
                                1)));

        assertThat(titleResult.activityVersion()).isEqualTo(1);
        assertThat(categoryResult.activityVersion()).isEqualTo(2);
        assertThat(jdbc.queryForMap(
                        """
                        SELECT title, category, version
                        FROM calendar_activity WHERE id = ?
                        """,
                        activityId))
                .containsEntry("title", "活動新名")
                .containsEntry("category", "交通")
                .containsEntry("version", 2L);
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> editorMutations.changeActivity(
                                new CalendarEditorActivityChange(
                                        "activity-outside",
                                        share.id(),
                                        laterActivity,
                                        CalendarEditorActivityChange.Field.TITLE,
                                        "不應成功",
                                        0))))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("authorized activity");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_editor_mutation_audit
                        WHERE activity_id = ?
                        """,
                        Long.class,
                        activityId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'EDITOR_MUTATION'
                        """,
                        Long.class))
                .isEqualTo(2);
    }

    @Test
    void wholePlanEditorCanMutateNodeAndActivity() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "whole-editor-node",
                                        "整份計畫節點",
                                        NOW),
                                NOW))
                        .getId());
        UUID activityId = inContext(
                fixture.ownerContext(),
                () -> activities
                        .saveAndFlush(CalendarActivityEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                "整份計畫活動",
                                CalendarPlacement.point(
                                        NOW,
                                        ZoneId.of("Asia/Taipei")),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "whole-editor-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "whole-editor-role",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));

        CalendarEditorMutationResult nodeResult = runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeNodeLabel(
                        new CalendarEditorNodeLabelChange(
                                "whole-editor-node-change",
                                share.id(),
                                nodeId,
                                "整份計畫節點已編輯",
                                1)));
        CalendarEditorActivityMutationResult activityResult = runtime(
                fixture.recipientContext(),
                () -> editorMutations.changeActivity(
                        new CalendarEditorActivityChange(
                                "whole-editor-activity-change",
                                share.id(),
                                activityId,
                                CalendarEditorActivityChange.Field.TITLE,
                                "整份計畫活動已編輯",
                                0)));

        assertThat(nodeResult.nodeRevision()).isEqualTo(2);
        assertThat(activityResult.activityVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT label FROM calendar_time_node
                        WHERE id = ?
                        """,
                        String.class,
                        nodeId))
                .isEqualTo("整份計畫節點已編輯");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT title FROM calendar_activity
                        WHERE id = ?
                        """,
                        String.class,
                        activityId))
                .isEqualTo("整份計畫活動已編輯");
        assertThat(count("calendar_editor_mutation_audit"))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM calendar_share_outbox
                        WHERE event_type = 'EDITOR_MUTATION'
                        """,
                        Long.class))
                .isEqualTo(2);
    }

    @Test
    void authoritativeCapabilityIsSeparateScopedIdempotentAndRevocable() {
        Fixture fixture = fixture();
        UUID nodeId = inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        "authoritative-node",
                                        "權威節點",
                                        NOW),
                                NOW))
                        .getId());
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.grantViewer(
                        "authoritative-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1,
                        CalendarShareScope.selectedNodes(List.of(nodeId))));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "authoritative-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityGrant grant =
                new CalendarAuthoritativeCapabilityGrant(
                        "authoritative-node-grant",
                        share.id(),
                        CalendarAuthoritativeCapabilityGrant.Scope.NODE,
                        nodeId,
                        2);

        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> authoritativeCapabilities.grant(grant));
        CalendarAuthoritativeCapabilityView replay = inContext(
                fixture.ownerContext(),
                () -> authoritativeCapabilities.grant(grant));

        assertThat(replay).isEqualTo(capability);
        assertThat(capability.status())
                .isEqualTo(
                        CalendarAuthoritativeCapabilityView.Status.ACTIVE);
        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> authoritativeCapabilities.grant(
                                new CalendarAuthoritativeCapabilityGrant(
                                        "authoritative-plan-too-wide",
                                        share.id(),
                                        CalendarAuthoritativeCapabilityGrant
                                                .Scope.PLAN,
                                        null,
                                        2))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("whole-plan");

        CalendarAuthoritativeCapabilityView revoked = inContext(
                fixture.ownerContext(),
                () -> authoritativeCapabilities.revoke(
                        "authoritative-revoke",
                        capability.id(),
                        1));
        CalendarAuthoritativeCapabilityView revokeReplay = inContext(
                fixture.ownerContext(),
                () -> authoritativeCapabilities.revoke(
                        "authoritative-revoke",
                        capability.id(),
                        1));

        assertThat(revokeReplay).isEqualTo(revoked);
        assertThat(revoked.status())
                .isEqualTo(
                        CalendarAuthoritativeCapabilityView.Status.REVOKED);
        assertThat(revoked.revision()).isEqualTo(2);
        assertThat(jdbc.queryForList(
                        """
                        SELECT event_type FROM calendar_share_outbox
                        WHERE event_type LIKE
                            'AUTHORITATIVE_CAPABILITY_%'
                        ORDER BY created_at, event_type
                        """,
                        String.class))
                .containsExactly(
                        "AUTHORITATIVE_CAPABILITY_GRANTED",
                        "AUTHORITATIVE_CAPABILITY_REVOKED");
    }

    private int insertMatchingEditorAuditAndOutboxWithoutUpdate(
            UUID shareId,
            UUID nodeId,
            String afterLabel,
            String requestKey) {
        UUID mutationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_editor_mutation_audit (
                    id, mutation_id, share_id, plan_id, node_id,
                    activity_id, mutation_kind, before_value,
                    after_value, previous_target_revision,
                    current_target_revision, operation_request_hash,
                    occurred_at, workspace_id, created_by_user_id,
                    editor_user_id)
                SELECT gen_random_uuid(), ?, ?, node_row.plan_id,
                       node_row.id, NULL, 'NODE_LABEL',
                       node_row.label, ?, node_row.revision,
                       node_row.revision + 1, ?, CURRENT_TIMESTAMP,
                       node_row.workspace_id,
                       node_row.created_by_user_id, ?
                FROM calendar_time_node node_row
                WHERE node_row.id = ?
                """,
                mutationId,
                shareId,
                afterLabel,
                CalendarShareService.hash(requestKey),
                WorkspaceContextHolder.requireContext().actorId(),
                nodeId);
        return jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id,
                    created_by_user_id, editor_mutation_id)
                SELECT gen_random_uuid(), ?, 'EDITOR_MUTATION',
                       share_row.id, NULL,
                       share_row.grantee_user_id, ?,
                       'PENDING', CURRENT_TIMESTAMP,
                       share_row.workspace_id,
                       share_row.created_by_user_id, ?
                FROM calendar_share share_row
                WHERE share_row.id = ?
                """,
                CalendarShareService.hash(
                        "EDITOR_MUTATION|" + mutationId),
                "mutationId=" + mutationId
                        + ";forged without target update",
                mutationId,
                shareId);
    }

    private Fixture fixture() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(owner, "selected owner");
        seedUser(recipient, "selected recipient");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'selected household', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        WorkspaceContext ownerContext = context(owner, workspace);
        UUID planId = inContext(ownerContext, () -> {
            UUID id = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    id,
                    "selected plan",
                    CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            return id;
        });
        return new Fixture(
                ownerContext, context(recipient, workspace), recipient, planId);
    }

    private List<String> visibleNodeLabels() {
        return jdbc.queryForList(
                "SELECT label FROM calendar_time_node ORDER BY label",
                String.class);
    }

    private long scopeItemCount(String kind) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM calendar_share_scope_item"
                        + " WHERE target_kind = ?",
                Long.class,
                kind);
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table, Long.class);
    }

    private void seedUser(UUID id, String name) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                name);
    }

    private void addMember(
            UUID workspace, UUID user, UUID creator, String role) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id,
                    joined_at, updated_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspace,
                user,
                role,
                creator);
    }

    private static WorkspaceContext context(UUID actor, UUID workspace) {
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions).execute(status -> {
                jdbc.execute("SET LOCAL ROLE " + RUNTIME_ROLE);
                return work.get();
            });
        }
    }

    private record Fixture(
            WorkspaceContext ownerContext,
            WorkspaceContext recipientContext,
            UUID recipient,
            UUID planId) {}

    private record TargetWithSibling(
            UUID activityId, UUID selectedNodeId, UUID siblingNodeId) {}
}
