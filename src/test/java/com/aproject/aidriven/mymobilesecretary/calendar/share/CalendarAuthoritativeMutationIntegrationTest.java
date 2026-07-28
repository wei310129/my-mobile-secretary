package com.aproject.aidriven.mymobilesecretary.calendar.share;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.adoption.CalendarLocation;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarTimeNode;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeRepository;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CalendarAuthoritativeMutationIntegrationTest
        extends IntegrationTestBase {

    private static final String RUNTIME_ROLE =
            "mms_calendar_authoritative_runtime";
    private static final Instant NOW =
            Instant.parse("2026-07-26T09:00:00Z");

    @Autowired private CalendarShareService shares;
    @Autowired
    private CalendarAuthoritativeCapabilityService capabilities;
    @Autowired
    private CalendarAuthoritativeMutationService mutations;
    @Autowired private CalendarPlanRepository plans;
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
                            'mms_calendar_authoritative_runtime') THEN
                        CREATE ROLE mms_calendar_authoritative_runtime
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
    void nodeCapabilityMutatesTimeAndLocationExactlyOnce() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "target", NOW);
        UUID otherNodeId =
                node(fixture, "outside", NOW.plusSeconds(60));
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
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "authoritative-capability",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.NODE,
                                nodeId,
                                2)));
        Instant revisedTime = NOW.plusSeconds(3600);
        CalendarAuthoritativeTimeChange timeChange =
                new CalendarAuthoritativeTimeChange(
                        "authoritative-time",
                        capability.id(),
                        nodeId,
                        revisedTime,
                        1,
                        "船班延後",
                        "provider-update");

        CalendarAuthoritativeMutationResult first = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseAbsoluteTime(timeChange));
        CalendarAuthoritativeMutationResult replay = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseAbsoluteTime(timeChange));
        CalendarAuthoritativeMutationResult semanticReplay = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseAbsoluteTime(
                        new CalendarAuthoritativeTimeChange(
                                "authoritative-time-alias",
                                capability.id(),
                                nodeId,
                                revisedTime,
                                1,
                                "船班延後",
                                "provider-update")));
        CalendarAuthoritativeMutationResult location = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseLocation(
                        new CalendarAuthoritativeLocationChange(
                                "authoritative-location",
                                capability.id(),
                                nodeId,
                                new CalendarLocation(
                                        "第二碼頭", 25.05, 121.52),
                                2,
                                "碼頭異動",
                                "provider-update")));
        CalendarAuthoritativeMutationResult cancellation = runtime(
                fixture.recipientContext(),
                () -> mutations.cancelNode(
                        new CalendarAuthoritativeCancellationChange(
                                "authoritative-cancel",
                                capability.id(),
                                nodeId,
                                3,
                                "班次取消",
                                "provider-update")));

        assertThat(replay).isEqualTo(first);
        assertThat(semanticReplay).isEqualTo(first);
        assertThat(location.nodeRevision()).isEqualTo(3);
        assertThat(cancellation.nodeRevision()).isEqualTo(4);
        assertThat(jdbc.queryForMap(
                        """
                        SELECT location_label, latitude, longitude,
                               cancellation_status, canceled_at,
                               revision, version
                        FROM calendar_time_node WHERE id = ?
                        """,
                        nodeId))
                .containsEntry("location_label", "第二碼頭")
                .containsEntry("latitude", 25.05)
                .containsEntry("longitude", 121.52)
                .containsEntry(
                        "cancellation_status", "CANCELED")
                .containsEntry("revision", 4L)
                .containsEntry("version", 3L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT absolute_time = resolved_time
                               AND absolute_time = ?
                        FROM calendar_time_node WHERE id = ?
                        """,
                        Boolean.class,
                        Timestamp.from(revisedTime),
                        nodeId))
                .isTrue();
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(3);
        assertThat(eventCount("AUTHORITATIVE_MUTATION")).isEqualTo(3);

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> mutations.reviseAbsoluteTime(
                                new CalendarAuthoritativeTimeChange(
                                        "time-after-cancel",
                                        capability.id(),
                                        nodeId,
                                        revisedTime.plusSeconds(300),
                                        4,
                                        "取消後不得改時間",
                                        "test"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(
                        "canceled calendar node cannot be revised");
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> mutations.reviseLocation(
                                new CalendarAuthoritativeLocationChange(
                                        "location-after-cancel",
                                        capability.id(),
                                        nodeId,
                                        new CalendarLocation(
                                                "取消後地點",
                                                25.06,
                                                121.53),
                                        4,
                                        "取消後不得改地點",
                                        "test"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(
                        "canceled calendar node cannot be revised");
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(3);
        assertThat(eventCount("AUTHORITATIVE_MUTATION")).isEqualTo(3);

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> mutations.reviseLocation(
                                new CalendarAuthoritativeLocationChange(
                                        "authoritative-outside",
                                        capability.id(),
                                        otherNodeId,
                                        new CalendarLocation(
                                                "不應成功",
                                                25.0,
                                                121.5),
                                        1,
                                        "越界測試",
                                        "test"))))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(3);
    }

    @Test
    void concurrentSemanticAliasesConvergeToOneMutation()
            throws Exception {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "concurrent", NOW);
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "concurrent-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "concurrent-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "concurrent-capability",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.PLAN,
                                null,
                                2)));
        Instant revisedTime = NOW.plusSeconds(1800);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CalendarAuthoritativeMutationResult> first =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return runtime(
                                fixture.recipientContext(),
                                () -> mutations.reviseAbsoluteTime(
                                        new CalendarAuthoritativeTimeChange(
                                                "semantic-race-a",
                                                capability.id(),
                                                nodeId,
                                                revisedTime,
                                                1,
                                                "同語意併發",
                                                "test")));
                    });
            Future<CalendarAuthoritativeMutationResult> second =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return runtime(
                                fixture.recipientContext(),
                                () -> mutations.reviseAbsoluteTime(
                                        new CalendarAuthoritativeTimeChange(
                                                "semantic-race-b",
                                                capability.id(),
                                                nodeId,
                                                revisedTime,
                                                1,
                                                "同語意併發",
                                                "test")));
                    });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            CalendarAuthoritativeMutationResult firstResult =
                    first.get(15, TimeUnit.SECONDS);
            CalendarAuthoritativeMutationResult secondResult =
                    second.get(15, TimeUnit.SECONDS);

            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(count("calendar_authoritative_mutation_audit"))
                    .isEqualTo(1);
            assertThat(eventCount("AUTHORITATIVE_MUTATION"))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT revision FROM calendar_time_node
                            WHERE id = ?
                            """,
                            Long.class,
                            nodeId))
                    .isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mutationAndExplicitCapabilityRevokeSerializeWithoutPartialConsequences()
            throws Exception {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "capability-revoke-race", NOW);
        CalendarAuthoritativeCapabilityView capability =
                grantPlanCapability(fixture, "capability-revoke-race");
        UUID ruleId = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "RELATIVE",
                -600L,
                null,
                "PENDING",
                NOW.minusSeconds(600));
        Instant revisedTime = NOW.plusSeconds(2400);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RaceMutationOutcome> mutation = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    runtime(
                            fixture.recipientContext(),
                            () -> mutations.reviseAbsoluteTime(
                                    new CalendarAuthoritativeTimeChange(
                                            "capability-revoke-race-mutation",
                                            capability.id(),
                                            nodeId,
                                            revisedTime,
                                            1,
                                            "能力撤銷競態",
                                            "test")));
                    return new RaceMutationOutcome(true, null);
                } catch (RuntimeException exception) {
                    return new RaceMutationOutcome(false, exception);
                }
            });
            Future<CalendarAuthoritativeCapabilityView> revoke =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return inContext(
                                fixture.ownerContext(),
                                () -> capabilities.revoke(
                                        "capability-revoke-race-revoke",
                                        capability.id(),
                                        1));
                    });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            RaceMutationOutcome outcome =
                    mutation.get(15, TimeUnit.SECONDS);
            CalendarAuthoritativeCapabilityView revoked =
                    revoke.get(15, TimeUnit.SECONDS);

            assertThat(revoked.status())
                    .isEqualTo(
                            CalendarAuthoritativeCapabilityView.Status
                                    .REVOKED);
            assertSerializedMutationConsequences(
                    outcome, nodeId, ruleId, revisedTime);
            assertThat(eventCount(
                            "AUTHORITATIVE_CAPABILITY_REVOKED"))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void mutationAndShareDowngradeSerializeWithoutPartialConsequences()
            throws Exception {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "share-downgrade-race", NOW);
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "share-downgrade-race-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "share-downgrade-race-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "share-downgrade-race-capability",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.PLAN,
                                null,
                                2)));
        UUID ruleId = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "RELATIVE",
                -600L,
                null,
                "PENDING",
                NOW.minusSeconds(600));
        Instant revisedTime = NOW.plusSeconds(3000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RaceMutationOutcome> mutation = executor.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    runtime(
                            fixture.recipientContext(),
                            () -> mutations.reviseAbsoluteTime(
                                    new CalendarAuthoritativeTimeChange(
                                            "share-downgrade-race-mutation",
                                            capability.id(),
                                            nodeId,
                                            revisedTime,
                                            1,
                                            "分享降權競態",
                                            "test")));
                    return new RaceMutationOutcome(true, null);
                } catch (RuntimeException exception) {
                    return new RaceMutationOutcome(false, exception);
                }
            });
            Future<CalendarShareView> downgrade =
                    executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return inContext(
                                fixture.ownerContext(),
                                () -> shares.changeRole(
                                        new CalendarShareRoleChange(
                                                "share-downgrade-race-viewer",
                                                share.id(),
                                                CalendarSharePermission
                                                        .VIEWER,
                                                2)));
                    });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            RaceMutationOutcome outcome =
                    mutation.get(15, TimeUnit.SECONDS);
            CalendarShareView downgraded =
                    downgrade.get(15, TimeUnit.SECONDS);

            assertThat(downgraded.revision()).isEqualTo(3);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT permission FROM calendar_share
                            WHERE id = ?
                            """,
                            String.class,
                            share.id()))
                    .isEqualTo("VIEWER");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT status
                            FROM calendar_authoritative_editor_capability
                            WHERE id = ?
                            """,
                            String.class,
                            capability.id()))
                    .isEqualTo("REVOKED");
            assertSerializedMutationConsequences(
                    outcome, nodeId, ruleId, revisedTime);
            assertThat(eventCount(
                            "AUTHORITATIVE_CAPABILITY_REVOKED"))
                    .isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void matchingAuditAndOutboxWithoutTargetUpdateRollsBack() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "missing-target-update", NOW);
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "missing-update-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "missing-update-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "missing-update-capability",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.PLAN,
                                null,
                                2)));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> forgedAuditAndOutboxWithoutUpdate(
                                capability.id(),
                                share.id(),
                                nodeId,
                                NOW.plusSeconds(2400))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isZero();
        assertThat(eventCount("AUTHORITATIVE_MUTATION")).isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT revision FROM calendar_time_node
                        WHERE id = ?
                        """,
                        Long.class,
                        nodeId))
                .isEqualTo(1);
    }

    @Test
    void authoritativeTimeMutationReconcilesOnlyOwnerReminderConsequencesExactlyOnce() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "reminder-time", NOW);
        CalendarAuthoritativeCapabilityView capability =
                grantPlanCapability(fixture, "reminder-time");
        UUID relativeRule = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "RELATIVE",
                -900L,
                null,
                "PENDING",
                NOW.minusSeconds(900));
        seedReminderOccurrence(
                fixture,
                relativeRule,
                nodeId,
                1,
                "ENQUEUED",
                NOW.minusSeconds(800));
        UUID absoluteRule = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "ABSOLUTE",
                null,
                NOW.minusSeconds(1800),
                "PENDING",
                NOW.minusSeconds(1800));
        UUID sharedTemplateRule = seedReminder(
                fixture,
                nodeId,
                "SHARED_TEMPLATE",
                "RELATIVE",
                -1200L,
                null,
                "PENDING",
                NOW.minusSeconds(1200));
        Instant revisedTime = NOW.plus(Duration.ofHours(1));
        CalendarAuthoritativeTimeChange change =
                new CalendarAuthoritativeTimeChange(
                        "reminder-time-change",
                        capability.id(),
                        nodeId,
                        revisedTime,
                        1,
                        "行程延後",
                        "test");

        CalendarAuthoritativeMutationResult first = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseAbsoluteTime(change));
        CalendarAuthoritativeMutationResult replay = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseAbsoluteTime(change));

        assertThat(replay).isEqualTo(first);
        assertThat(reminderRuleStatus(relativeRule)).isEqualTo("ACTIVE");
        assertThat(reminderRuleStatus(absoluteRule))
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(reminderRuleStatus(sharedTemplateRule))
                .isEqualTo("ACTIVE");
        assertThat(reminderOccurrenceCount(relativeRule, "CANCELED"))
                .isEqualTo(2);
        assertThat(reminderOccurrenceCount(relativeRule, "ENQUEUED"))
                .isZero();
        assertThat(reminderOccurrenceCount(absoluteRule, "CANCELED"))
                .isEqualTo(1);
        assertThat(reminderOccurrenceCount(
                        sharedTemplateRule, "CANCELED"))
                .isEqualTo(1);
        assertThat(reminderOccurrenceCount(relativeRule, "PENDING"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_reminder_occurrence
                        WHERE rule_id = ? AND status = 'PENDING'
                          AND node_revision = 2
                          AND rule_revision = 1
                          AND sequence_number = 0
                          AND scheduled_at = ?
                        """,
                        Long.class,
                        relativeRule,
                        Timestamp.from(revisedTime.minusSeconds(900))))
                .isEqualTo(1L);
        assertThat(reminderOccurrenceCount(
                        sharedTemplateRule, "PENDING"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_reminder_occurrence
                        WHERE node_id = ?
                        """,
                        Long.class,
                        nodeId))
                .isEqualTo(5L);
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(1);
        assertThat(eventCount("AUTHORITATIVE_MUTATION"))
                .isEqualTo(1);
    }

    @Test
    void authoritativeLocationMutationLeavesExistingRemindersUntouched() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "reminder-location", NOW);
        CalendarAuthoritativeCapabilityView capability =
                grantPlanCapability(fixture, "reminder-location");
        UUID relativeRule = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "RELATIVE",
                -600L,
                null,
                "PENDING",
                NOW.minusSeconds(600));
        seedReminderOccurrence(
                fixture,
                relativeRule,
                nodeId,
                1,
                "ENQUEUED",
                NOW.minusSeconds(500));
        UUID absoluteRule = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "ABSOLUTE",
                null,
                NOW.minusSeconds(1200),
                "PENDING",
                NOW.minusSeconds(1200));
        ReminderSnapshot before = reminderSnapshot(nodeId);

        CalendarAuthoritativeMutationResult result = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseLocation(
                        new CalendarAuthoritativeLocationChange(
                                "reminder-location-change",
                                capability.id(),
                                nodeId,
                                new CalendarLocation(
                                        "第三碼頭", 25.051, 121.521),
                                1,
                                "碼頭異動",
                                "test")));

        assertThat(result.nodeRevision()).isEqualTo(2);
        assertThat(reminderSnapshot(nodeId)).isEqualTo(before);
        assertThat(reminderRuleStatus(relativeRule)).isEqualTo("ACTIVE");
        assertThat(reminderRuleStatus(absoluteRule)).isEqualTo("ACTIVE");
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(1);
        assertThat(eventCount("AUTHORITATIVE_MUTATION"))
                .isEqualTo(1);
    }

    @Test
    void authoritativeCancellationTerminatesPendingAndEnqueuedRemindersExactlyOnce() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "reminder-cancel", NOW);
        CalendarAuthoritativeCapabilityView capability =
                grantPlanCapability(fixture, "reminder-cancel");
        UUID pendingRelative = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "RELATIVE",
                -600L,
                null,
                "PENDING",
                NOW.minusSeconds(600));
        UUID enqueuedAbsolute = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "ABSOLUTE",
                null,
                NOW.minusSeconds(1200),
                "ENQUEUED",
                NOW.minusSeconds(1200));
        UUID pendingTemplate = seedReminder(
                fixture,
                nodeId,
                "SHARED_TEMPLATE",
                "RELATIVE",
                -1800L,
                null,
                "PENDING",
                NOW.minusSeconds(1800));
        CalendarAuthoritativeCancellationChange change =
                new CalendarAuthoritativeCancellationChange(
                        "reminder-cancel-change",
                        capability.id(),
                        nodeId,
                        1,
                        "行程取消",
                        "test");

        CalendarAuthoritativeMutationResult first = runtime(
                fixture.recipientContext(),
                () -> mutations.cancelNode(change));
        CalendarAuthoritativeMutationResult replay = runtime(
                fixture.recipientContext(),
                () -> mutations.cancelNode(change));

        assertThat(replay).isEqualTo(first);
        assertThat(List.of(
                        reminderRuleStatus(pendingRelative),
                        reminderRuleStatus(enqueuedAbsolute),
                        reminderRuleStatus(pendingTemplate)))
                .containsOnly("CANCELED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_reminder_occurrence
                        WHERE node_id = ? AND status = 'CANCELED'
                        """,
                        Long.class,
                        nodeId))
                .isEqualTo(3L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_reminder_occurrence
                        WHERE node_id = ?
                          AND status IN ('PENDING', 'ENQUEUED')
                        """,
                        Long.class,
                        nodeId))
                .isZero();
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(1);
        assertThat(eventCount("AUTHORITATIVE_MUTATION"))
                .isEqualTo(1);
    }

    @Test
    void forgedTimeMutationMissingReminderConsequencesRollsBackAtomically() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "forged-reminder", NOW);
        CalendarAuthoritativeCapabilityView capability =
                grantPlanCapability(fixture, "forged-reminder");
        UUID ruleId = seedReminder(
                fixture,
                nodeId,
                "PERSONAL",
                "RELATIVE",
                -300L,
                null,
                "PENDING",
                NOW.minusSeconds(300));

        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> forgedTimeMutationWithoutReminderConsequences(
                                capability.id(),
                                capability.shareId(),
                                nodeId,
                                NOW.plusSeconds(1800))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining(
                        "exact reminder consequence");

        assertThat(jdbc.queryForObject(
                        """
                        SELECT revision FROM calendar_time_node
                        WHERE id = ?
                        """,
                        Long.class,
                        nodeId))
                .isEqualTo(1);
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isZero();
        assertThat(eventCount("AUTHORITATIVE_MUTATION")).isZero();
        assertThat(reminderOccurrenceCount(ruleId, "PENDING"))
                .isEqualTo(1);
        assertThat(reminderOccurrenceCount(ruleId, "CANCELED"))
                .isZero();
    }

    @Test
    void revokedCapabilityAndFakeMarkerCannotMutate() {
        Fixture fixture = fixture();
        UUID nodeId = node(fixture, "revoked", NOW);
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        "revoked-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        "revoked-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        CalendarAuthoritativeCapabilityView capability = inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                "revoked-capability",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.PLAN,
                                null,
                                2)));

        assertThatThrownBy(() -> inContext(
                        fixture.ownerContext(),
                        () -> jdbc.update(
                                """
                                DELETE FROM
                                    calendar_authoritative_editor_capability
                                WHERE id = ?
                                """,
                                capability.id())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("deletion is forbidden");
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> fakeTimeUpdate(nodeId)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("calendar authoritative");
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> forgedAuditedTimeUpdate(
                                capability.id(),
                                share.id(),
                                nodeId,
                                NOW.plusSeconds(700))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("durable outbox");
        CalendarAuthoritativeMutationResult planMutation = runtime(
                fixture.recipientContext(),
                () -> mutations.reviseAbsoluteTime(
                        new CalendarAuthoritativeTimeChange(
                                "plan-capability-time",
                                capability.id(),
                                nodeId,
                                NOW.plusSeconds(900),
                                1,
                                "PLAN capability 成功路徑",
                                "test")));
        assertThat(planMutation.nodeRevision()).isEqualTo(2);
        inContext(
                fixture.ownerContext(),
                () -> capabilities.revoke(
                        "revoke-now", capability.id(), 1));
        assertThatThrownBy(() -> runtime(
                        fixture.recipientContext(),
                        () -> mutations.reviseAbsoluteTime(
                                new CalendarAuthoritativeTimeChange(
                                        "after-revoke",
                                        capability.id(),
                                        nodeId,
                                        NOW.plusSeconds(1200),
                                        2,
                                        "撤銷後測試",
                                        "test"))))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT revision FROM calendar_time_node WHERE id = ?",
                        Long.class,
                        nodeId))
                .isEqualTo(2);
    }

    private int fakeTimeUpdate(UUID nodeId) {
        jdbc.queryForObject(
                """
                SELECT set_config(
                  'app.calendar_authoritative_mutation_id', ?, true)
                """,
                String.class,
                UUID.randomUUID().toString());
        return jdbc.update(
                """
                UPDATE calendar_time_node
                SET absolute_time = ?, resolved_time = ?,
                    revision = revision + 1,
                    version = version + 1,
                    updated_at =
                        GREATEST(updated_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """,
                Timestamp.from(NOW.plusSeconds(600)),
                Timestamp.from(NOW.plusSeconds(600)),
                nodeId);
    }

    private int forgedAuditedTimeUpdate(
            UUID capabilityId,
            UUID shareId,
            UUID nodeId,
            Instant afterTime) {
        UUID mutationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_authoritative_mutation_audit (
                    id, mutation_id, capability_id, share_id, plan_id,
                    node_id, mutation_kind, reason, source,
                    before_value, after_value,
                    previous_target_revision, current_target_revision,
                    operation_request_hash, occurred_at, workspace_id,
                    created_by_user_id, editor_user_id)
                SELECT gen_random_uuid(), ?, ?, ?, node_row.plan_id,
                       node_row.id, 'NODE_TIME', 'forged', 'test',
                       node_row.absolute_time::text,
                       (?::timestamptz)::text,
                       node_row.revision, node_row.revision + 1,
                       ?, CURRENT_TIMESTAMP, node_row.workspace_id,
                       node_row.created_by_user_id, ?
                FROM calendar_time_node node_row
                WHERE node_row.id = ?
                """,
                mutationId,
                capabilityId,
                shareId,
                Timestamp.from(afterTime),
                CalendarShareService.hash(
                        "forged|" + mutationId),
                WorkspaceContextHolder.requireContext().actorId(),
                nodeId);
        jdbc.queryForObject(
                """
                SELECT set_config(
                  'app.calendar_authoritative_mutation_id', ?, true)
                """,
                String.class,
                mutationId.toString());
        return jdbc.update(
                """
                UPDATE calendar_time_node
                SET absolute_time = ?, resolved_time = ?,
                    revision = revision + 1,
                    version = version + 1,
                    updated_at =
                        GREATEST(updated_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """,
                Timestamp.from(afterTime),
                Timestamp.from(afterTime),
                nodeId);
    }

    private int forgedAuditAndOutboxWithoutUpdate(
            UUID capabilityId,
            UUID shareId,
            UUID nodeId,
            Instant afterTime) {
        UUID mutationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_authoritative_mutation_audit (
                    id, mutation_id, capability_id, share_id, plan_id,
                    node_id, mutation_kind, reason, source,
                    before_value, after_value,
                    previous_target_revision, current_target_revision,
                    operation_request_hash, occurred_at, workspace_id,
                    created_by_user_id, editor_user_id)
                SELECT gen_random_uuid(), ?, ?, ?, node_row.plan_id,
                       node_row.id, 'NODE_TIME', 'forged', 'test',
                       node_row.absolute_time::text,
                       (?::timestamptz)::text,
                       node_row.revision, node_row.revision + 1,
                       ?, CURRENT_TIMESTAMP, node_row.workspace_id,
                       node_row.created_by_user_id, ?
                FROM calendar_time_node node_row
                WHERE node_row.id = ?
                """,
                mutationId,
                capabilityId,
                shareId,
                Timestamp.from(afterTime),
                CalendarShareService.hash(
                        "forged-no-update|" + mutationId),
                WorkspaceContextHolder.requireContext().actorId(),
                nodeId);
        return jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id,
                    created_by_user_id)
                SELECT gen_random_uuid(), ?,
                       'AUTHORITATIVE_MUTATION', share_row.id,
                       NULL, share_row.grantee_user_id, ?,
                       'PENDING', CURRENT_TIMESTAMP,
                       share_row.workspace_id,
                       share_row.created_by_user_id
                FROM calendar_share share_row
                WHERE share_row.id = ?
                """,
                CalendarShareService.hash(
                        "AUTHORITATIVE_MUTATION|" + mutationId),
                "mutationId=" + mutationId
                        + ";kind=NODE_TIME;forged=true",
                shareId);
    }

    private int forgedTimeMutationWithoutReminderConsequences(
            UUID capabilityId,
            UUID shareId,
            UUID nodeId,
            Instant afterTime) {
        UUID mutationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO calendar_authoritative_mutation_audit (
                    id, mutation_id, capability_id, share_id, plan_id,
                    node_id, mutation_kind, reason, source,
                    before_value, after_value,
                    previous_target_revision, current_target_revision,
                    operation_request_hash, occurred_at, workspace_id,
                    created_by_user_id, editor_user_id)
                SELECT gen_random_uuid(), ?, ?, ?, node_row.plan_id,
                       node_row.id, 'NODE_TIME', 'forged', 'test',
                       node_row.absolute_time::text,
                       (?::timestamptz)::text,
                       node_row.revision, node_row.revision + 1,
                       ?, CURRENT_TIMESTAMP, node_row.workspace_id,
                       node_row.created_by_user_id, ?
                FROM calendar_time_node node_row
                WHERE node_row.id = ?
                """,
                mutationId,
                capabilityId,
                shareId,
                Timestamp.from(afterTime),
                CalendarShareService.hash(
                        "forged-reminder|" + mutationId),
                WorkspaceContextHolder.requireContext().actorId(),
                nodeId);
        jdbc.queryForObject(
                """
                SELECT set_config(
                  'app.calendar_authoritative_mutation_id', ?, true)
                """,
                String.class,
                mutationId.toString());
        jdbc.update(
                """
                UPDATE calendar_time_node
                SET absolute_time = ?, resolved_time = ?,
                    revision = revision + 1,
                    version = version + 1,
                    updated_at =
                        GREATEST(updated_at, CURRENT_TIMESTAMP)
                WHERE id = ?
                """,
                Timestamp.from(afterTime),
                Timestamp.from(afterTime),
                nodeId);
        return jdbc.update(
                """
                INSERT INTO calendar_share_outbox (
                    id, operation_request_hash, event_type, share_id,
                    content_grant_id, grantee_user_id, payload_text,
                    status, created_at, workspace_id,
                    created_by_user_id, authoritative_mutation_id)
                SELECT gen_random_uuid(), ?,
                       'AUTHORITATIVE_MUTATION', share_row.id,
                       NULL, share_row.grantee_user_id, ?,
                       'PENDING', CURRENT_TIMESTAMP,
                       share_row.workspace_id,
                       share_row.created_by_user_id, ?
                FROM calendar_share share_row
                WHERE share_row.id = ?
                """,
                CalendarShareService.hash(
                        "AUTHORITATIVE_MUTATION|" + mutationId),
                "mutationId=" + mutationId
                        + ";kind=NODE_TIME;forged=true",
                mutationId,
                shareId);
    }

    private void assertSerializedMutationConsequences(
            RaceMutationOutcome outcome,
            UUID nodeId,
            UUID ruleId,
            Instant revisedTime) {
        Map<String, Object> nodeState = jdbc.queryForMap(
                """
                SELECT absolute_time, resolved_time, revision, version
                FROM calendar_time_node WHERE id = ?
                """,
                nodeId);
        long expectedMutationCount = outcome.succeeded() ? 1L : 0L;
        assertThat(count("calendar_authoritative_mutation_audit"))
                .isEqualTo(expectedMutationCount);
        assertThat(eventCount("AUTHORITATIVE_MUTATION"))
                .isEqualTo(expectedMutationCount);
        assertThat(reminderRuleStatus(ruleId)).isEqualTo("ACTIVE");
        if (outcome.succeeded()) {
            assertThat(outcome.failure()).isNull();
            assertThat(nodeState)
                    .containsEntry(
                            "absolute_time",
                            Timestamp.from(revisedTime))
                    .containsEntry(
                            "resolved_time",
                            Timestamp.from(revisedTime))
                    .containsEntry("revision", 2L)
                    .containsEntry("version", 1L);
            assertThat(reminderOccurrenceCount(ruleId, "CANCELED"))
                    .isEqualTo(1);
            assertThat(reminderOccurrenceCount(ruleId, "PENDING"))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            """
                            SELECT count(*)
                            FROM calendar_reminder_occurrence
                            WHERE rule_id = ? AND status = 'PENDING'
                              AND node_revision = 2
                              AND rule_revision = 1
                              AND sequence_number = 0
                              AND scheduled_at = ?
                            """,
                            Long.class,
                            ruleId,
                            Timestamp.from(
                                    revisedTime.minusSeconds(600))))
                    .isEqualTo(1L);
        } else {
            assertThat(outcome.failure())
                    .isInstanceOfAny(
                            NotFoundException.class,
                            BusinessException.class);
            assertThat(nodeState)
                    .containsEntry(
                            "absolute_time", Timestamp.from(NOW))
                    .containsEntry(
                            "resolved_time", Timestamp.from(NOW))
                    .containsEntry("revision", 1L)
                    .containsEntry("version", 0L);
            assertThat(reminderOccurrenceCount(ruleId, "PENDING"))
                    .isEqualTo(1);
            assertThat(reminderOccurrenceCount(ruleId, "CANCELED"))
                    .isZero();
        }
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM calendar_reminder_occurrence
                        WHERE rule_id = ?
                        """,
                        Long.class,
                        ruleId))
                .isEqualTo(outcome.succeeded() ? 2L : 1L);
    }

    private CalendarAuthoritativeCapabilityView grantPlanCapability(
            Fixture fixture, String key) {
        CalendarShareView share = inContext(
                fixture.ownerContext(),
                () -> shares.createViewerShare(
                        key + "-share",
                        fixture.planId(),
                        fixture.recipient(),
                        1));
        inContext(
                fixture.ownerContext(),
                () -> shares.changeRole(new CalendarShareRoleChange(
                        key + "-editor",
                        share.id(),
                        CalendarSharePermission.EDITOR,
                        1)));
        return inContext(
                fixture.ownerContext(),
                () -> capabilities.grant(
                        new CalendarAuthoritativeCapabilityGrant(
                                key + "-capability",
                                share.id(),
                                CalendarAuthoritativeCapabilityGrant
                                        .Scope.PLAN,
                                null,
                                2)));
    }

    private UUID seedReminder(
            Fixture fixture,
            UUID nodeId,
            String ownerKind,
            String ruleKind,
            Long offsetSeconds,
            Instant absoluteFireAt,
            String occurrenceStatus,
            Instant scheduledAt) {
        UUID ruleId = UUID.randomUUID();
        UUID occurrenceId = UUID.randomUUID();
        inContext(
                fixture.ownerContext(),
                () -> {
                    jdbc.update(
                            """
                            INSERT INTO calendar_reminder_rule (
                                id, plan_id, node_id, owner_kind,
                                rule_kind, offset_seconds,
                                absolute_fire_at, delivery_mode,
                                ack_interval_seconds, max_alerts,
                                preferred_channel, status, revision,
                                version, created_at, updated_at,
                                workspace_id, created_by_user_id,
                                source_created_by_user_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, 'ONCE',
                                    NULL, NULL, 'LOG', 'ACTIVE', 1, 0,
                                    ?, ?, ?, ?, ?)
                            """,
                            ruleId,
                            fixture.planId(),
                            nodeId,
                            ownerKind,
                            ruleKind,
                            offsetSeconds,
                            absoluteFireAt == null
                                    ? null
                                    : Timestamp.from(absoluteFireAt),
                            Timestamp.from(NOW),
                            Timestamp.from(NOW),
                            fixture.ownerContext().workspaceId(),
                            fixture.ownerContext().actorId(),
                            fixture.ownerContext().actorId());
                    return jdbc.update(
                            """
                            INSERT INTO calendar_reminder_occurrence (
                                id, rule_id, plan_id, node_id,
                                node_revision, rule_revision,
                                sequence_number, scheduled_at, status,
                                version, created_at, updated_at,
                                workspace_id, created_by_user_id,
                                source_created_by_user_id)
                            VALUES (?, ?, ?, ?, 1, 1, 0, ?, ?, 0,
                                    ?, ?, ?, ?, ?)
                            """,
                            occurrenceId,
                            ruleId,
                            fixture.planId(),
                            nodeId,
                            Timestamp.from(scheduledAt),
                            occurrenceStatus,
                            Timestamp.from(NOW),
                            Timestamp.from(NOW),
                            fixture.ownerContext().workspaceId(),
                            fixture.ownerContext().actorId(),
                            fixture.ownerContext().actorId());
                });
        return ruleId;
    }

    private void seedReminderOccurrence(
            Fixture fixture,
            UUID ruleId,
            UUID nodeId,
            int sequenceNumber,
            String status,
            Instant scheduledAt) {
        inContext(
                fixture.ownerContext(),
                () -> jdbc.update(
                        """
                        INSERT INTO calendar_reminder_occurrence (
                            id, rule_id, plan_id, node_id,
                            node_revision, rule_revision,
                            sequence_number, scheduled_at, status,
                            version, created_at, updated_at,
                            workspace_id, created_by_user_id,
                            source_created_by_user_id)
                        VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, 0,
                                ?, ?, ?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        ruleId,
                        fixture.planId(),
                        nodeId,
                        sequenceNumber,
                        Timestamp.from(scheduledAt),
                        status,
                        Timestamp.from(NOW),
                        Timestamp.from(NOW),
                        fixture.ownerContext().workspaceId(),
                        fixture.ownerContext().actorId(),
                        fixture.ownerContext().actorId()));
    }

    private ReminderSnapshot reminderSnapshot(UUID nodeId) {
        return new ReminderSnapshot(
                jdbc.queryForList(
                        """
                        SELECT * FROM calendar_reminder_rule
                        WHERE node_id = ? ORDER BY id
                        """,
                        nodeId),
                jdbc.queryForList(
                        """
                        SELECT * FROM calendar_reminder_occurrence
                        WHERE node_id = ? ORDER BY id
                        """,
                        nodeId));
    }

    private String reminderRuleStatus(UUID ruleId) {
        return jdbc.queryForObject(
                """
                SELECT status FROM calendar_reminder_rule
                WHERE id = ?
                """,
                String.class,
                ruleId);
    }

    private long reminderOccurrenceCount(
            UUID ruleId, String status) {
        return jdbc.queryForObject(
                """
                SELECT count(*)
                FROM calendar_reminder_occurrence
                WHERE rule_id = ? AND status = ?
                """,
                Long.class,
                ruleId,
                status);
    }

    private Fixture fixture() {
        UUID owner = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(owner, "authoritative owner");
        seedUser(recipient, "authoritative recipient");
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id,
                    created_at, updated_at)
                VALUES (?, 'authoritative household', 'HOUSEHOLD', ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                owner);
        addMember(workspace, owner, owner, "OWNER");
        addMember(workspace, recipient, owner, "MEMBER");
        WorkspaceContext ownerContext =
                context(owner, workspace);
        UUID planId = inContext(ownerContext, () -> {
            UUID id = UUID.randomUUID();
            plans.saveAndFlush(CalendarPlanEntity.create(
                    id,
                    "authoritative plan",
                    CalendarPlacement.point(
                            NOW, ZoneId.of("Asia/Taipei")),
                    NOW));
            return id;
        });
        return new Fixture(
                ownerContext,
                context(recipient, workspace),
                recipient,
                planId);
    }

    private UUID node(
            Fixture fixture, String key, Instant time) {
        return inContext(
                fixture.ownerContext(),
                () -> nodes.saveAndFlush(CalendarTimeNodeEntity.create(
                                UUID.randomUUID(),
                                fixture.planId(),
                                null,
                                CalendarTimeNode.absolute(
                                        key, key, time),
                                NOW))
                        .getId());
    }

    private void seedUser(UUID id, String name) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE',
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                id,
                name);
    }

    private void addMember(
            UUID workspace, UUID user, UUID creator, String role) {
        jdbc.update(
                """
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role,
                    created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                workspace,
                user,
                role,
                creator);
    }

    private long count(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table, Long.class);
    }

    private long eventCount(String eventType) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM calendar_share_outbox
                WHERE event_type = ?
                """,
                Long.class,
                eventType);
    }

    private static WorkspaceContext context(
            UUID actor, UUID workspace) {
        return new WorkspaceContext(
                actor, workspace, WorkspaceChannel.TEST);
    }

    private <T> T inContext(
            WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private <T> T runtime(
            WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored =
                WorkspaceContextHolder.open(context)) {
            return new TransactionTemplate(transactions)
                    .execute(status -> {
                        jdbc.execute(
                                "SET LOCAL ROLE " + RUNTIME_ROLE);
                        return work.get();
                    });
        }
    }

    private record Fixture(
            WorkspaceContext ownerContext,
            WorkspaceContext recipientContext,
            UUID recipient,
            UUID planId) {}

    private record RaceMutationOutcome(
            boolean succeeded, RuntimeException failure) {}

    private record ReminderSnapshot(
            List<Map<String, Object>> rules,
            List<Map<String, Object>> occurrences) {}
}
