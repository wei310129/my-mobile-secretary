package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.domain.CalendarPlacement;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarPlanRepository;
import com.aproject.aidriven.mymobilesecretary.calendar.task.CalendarTaskBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.task.CalendarTaskTarget;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.UserKnowledgeService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.ObjectAnnotation;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.UserKnowledgeFact;
import com.aproject.aidriven.mymobilesecretary.knowledge.persistence.ObjectAnnotationRepository;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.TaskPriority;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.calendar-v2.cutover-enabled=true")
class KnowledgeMaterializationIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-07-24T13:00:00Z");
    private static final String CHANNEL = "TEST";
    private static final String CONVERSATION_SCOPE = "knowledge-materialization-test";

    @Autowired private KnowledgeMaterializationService materializations;
    @Autowired private Clock clock;
    @Autowired private CalendarKnowledgeBindingService bindings;
    @Autowired private CalendarKnowledgeReadService reads;
    @Autowired private CalendarPlanRepository plans;
    @Autowired private CalendarTaskBindingService taskBindings;
    @Autowired private UserKnowledgeService facts;
    @Autowired private ObjectAnnotationRepository annotations;
    @Autowired private TaskService tasks;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void unconfirmedOrStaleConsentProducesNoDownstreamMutation() {
        WorkspaceContext context = seedContext("未確認 materialization");
        Approved approved = inContext(context, () -> approved("登船", "回報平安"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "回報平安", null, TaskPriority.NORMAL);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-unconfirmed",
                        source(approved),
                        command));

        assertThatThrownBy(() -> inContext(context, () -> materializations.materialize(
                        new KnowledgeMaterializationConsent(
                                "not-confirmed",
                                proposal.id(),
                                proposal.revision(),
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                false),
                        command)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inContext(context, () -> materializations.materialize(
                        new KnowledgeMaterializationConsent(
                                "stale-revision",
                                proposal.id(),
                                proposal.revision() - 1,
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                true),
                        command)))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("task")).isZero();
        assertThat(count("knowledge_materialization")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM knowledge_materialization",
                        String.class))
                .isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void sourceUpdateAfterPrepareProducesNoDownstreamMutation() {
        WorkspaceContext context = seedContext("來源更新 materialization");
        Approved approved = inContext(context, () -> approved("登船通知", "抵達後回報"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "抵達後回報", null, TaskPriority.NORMAL);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-source-update", source(approved), command));

        inContext(context, () -> facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE,
                "登船通知",
                "抵達後立即回報"));

        assertThatThrownBy(() -> inContext(context, () -> materializations.materialize(
                        new KnowledgeMaterializationConsent(
                                "confirm-source-update",
                                proposal.id(),
                                proposal.revision(),
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                true),
                        command)))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("task")).isZero();
        assertThat(count("calendar_task_binding")).isZero();
    }

    @Test
    void cancelReplayLeavesEveryDownstreamTargetUnchanged() {
        WorkspaceContext context = seedContext("取消 materialization");
        Approved approved = inContext(context, () -> approved("取消", "不要建立待辦"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "不應建立", null, TaskPriority.NORMAL);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-cancel", source(approved), command));

        KnowledgeMaterializationProposalView canceled =
                inContext(context, () -> materializations.cancel(
                        "cancel-once",
                        proposal.id(),
                        proposal.revision(),
                        CHANNEL,
                        CONVERSATION_SCOPE));
        KnowledgeMaterializationProposalView replay =
                inContext(context, () -> materializations.cancel(
                        "cancel-once",
                        proposal.id(),
                        proposal.revision(),
                        CHANNEL,
                        CONVERSATION_SCOPE));

        assertThat(replay).isEqualTo(canceled);
        assertThat(canceled.status())
                .isEqualTo(KnowledgeMaterializationProposalView.Status.CANCELED);
        assertThat(count("task")).isZero();
        assertThat(count("calendar_task_binding")).isZero();
    }

    @Test
    void scopedConfirmationRehydratesStoredCommandAndReplaysExactlyOnce() {
        WorkspaceContext context = seedContext("scoped confirmation");
        Approved approved = inContext(context, () -> approved("船票", "記得購買"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "購買船票", null, TaskPriority.HIGH);
        inContext(context, () -> materializations.prepare(
                "prepare-scoped-confirm", source(approved), command));

        KnowledgeMaterializationView first = inContext(
                context,
                () -> materializations.confirmPending(
                        "confirm-scoped-once",
                        CHANNEL,
                        CONVERSATION_SCOPE,
                        null));
        KnowledgeMaterializationView replay = inContext(
                context,
                () -> materializations.confirmPending(
                        "confirm-scoped-once",
                        CHANNEL,
                        CONVERSATION_SCOPE,
                        null));

        assertThat(replay).isEqualTo(first);
        assertThat(first.targetKind())
                .isEqualTo(KnowledgeMaterializationView.TargetKind.TASK);
        assertThat(count("task")).isEqualTo(1L);
        assertThat(count("calendar_task_binding")).isEqualTo(1L);
    }

    @Test
    void scopedConfirmationRequiresUniquePendingUnlessTrustedQuoteSelectsOne() {
        WorkspaceContext context = seedContext("scoped ambiguity");
        Approved approved = inContext(context, () -> approved("登船", "兩個提議"));
        KnowledgeMaterializationProposalView first = inContext(
                context,
                () -> materializations.prepare(
                        "prepare-first-pending",
                        source(approved),
                        new KnowledgeMaterializationCommand.CreateTask(
                                "第一件", null, TaskPriority.NORMAL)));
        KnowledgeMaterializationProposalView second = inContext(
                context,
                () -> materializations.prepare(
                        "prepare-second-pending",
                        source(approved),
                        new KnowledgeMaterializationCommand.CreateTask(
                                "第二件", null, TaskPriority.NORMAL)));

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> materializations.confirmPending(
                                "ambiguous-confirm",
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                null)))
                .isInstanceOf(com.aproject.aidriven.mymobilesecretary.shared.error
                        .BusinessException.class)
                .extracting("code")
                .isEqualTo("KNOWLEDGE_MATERIALIZATION_PENDING_NOT_UNIQUE");
        assertThat(count("task")).isZero();

        KnowledgeMaterializationView selected = inContext(
                context,
                () -> materializations.confirmPending(
                        "quoted-confirm",
                        CHANNEL,
                        CONVERSATION_SCOPE,
                        second.id()));

        assertThat(selected.targetKind())
                .isEqualTo(KnowledgeMaterializationView.TargetKind.TASK);
        assertThat(count("task")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM knowledge_materialization WHERE id = ?",
                        String.class,
                        first.id()))
                .isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void pendingConfirmationCannotCrossActorWorkspaceChannelOrConversationScope() {
        WorkspaceContext owner = seedContext("scope isolation owner");
        Approved approved = inContext(
                owner, () -> approved("隔離提案", "只允許原對話確認"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "原對話待辦", null, TaskPriority.NORMAL);
        KnowledgeMaterializationProposalView proposal = inContext(
                owner,
                () -> materializations.prepare(
                        "prepare-scope-isolation", source(approved), command));
        UUID peerId = UUID.randomUUID();
        seedUser(peerId, "same workspace peer");
        WorkspaceContext peer = new WorkspaceContext(
                peerId, owner.workspaceId(), WorkspaceChannel.TEST);
        WorkspaceContext outsider = seedContext("other workspace actor");

        assertThatThrownBy(() -> inContext(
                        peer,
                        () -> materializations.confirmPending(
                                "peer-confirm",
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                proposal.id())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> inContext(
                        outsider,
                        () -> materializations.confirmPending(
                                "outsider-confirm",
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                proposal.id())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> inContext(
                        owner,
                        () -> materializations.confirmPending(
                                "wrong-channel-confirm",
                                "REST",
                                CONVERSATION_SCOPE,
                                proposal.id())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> inContext(
                        owner,
                        () -> materializations.confirmPending(
                                "wrong-scope-confirm",
                                CHANNEL,
                                "another-conversation",
                                proposal.id())))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("task")).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM knowledge_materialization WHERE id = ?",
                        String.class,
                        proposal.id()))
                .isEqualTo("PENDING_CONFIRMATION");

        KnowledgeMaterializationView created = inContext(
                owner,
                () -> materializations.confirmPending(
                        "correct-scope-confirm",
                        CHANNEL,
                        CONVERSATION_SCOPE,
                        proposal.id()));
        assertThat(created.targetKind())
                .isEqualTo(KnowledgeMaterializationView.TargetKind.TASK);
        assertThat(count("task")).isEqualTo(1L);
    }

    @Test
    void scopedConfirmationRejectsCorruptedStoredSnapshotBeforeExecution() {
        WorkspaceContext context = seedContext("corrupt snapshot");
        Approved approved = inContext(context, () -> approved("損壞", "不可執行"));
        KnowledgeMaterializationProposalView proposal = inContext(
                context,
                () -> materializations.prepare(
                        "prepare-corrupt",
                        source(approved),
                        new KnowledgeMaterializationCommand.CreateTask(
                                "不可建立", null, TaskPriority.NORMAL)));
        jdbc.update(
                "UPDATE knowledge_materialization SET command_snapshot = ? WHERE id = ?",
                "TASK|999999999:x",
                proposal.id());

        assertThatThrownBy(() -> inContext(
                        context,
                        () -> materializations.confirmPending(
                                "confirm-corrupt",
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                proposal.id())))
                .isInstanceOf(com.aproject.aidriven.mymobilesecretary.shared.error
                        .BusinessException.class)
                .extracting("code")
                .isEqualTo("KNOWLEDGE_MATERIALIZATION_SNAPSHOT_INVALID");
        assertThat(count("task")).isZero();
        assertThat(count("calendar_task_binding")).isZero();
    }

    @Test
    void expiredProposalIsPersistedAndProducesNoDownstreamMutation() {
        WorkspaceContext context = seedContext("過期 materialization");
        Approved approved = inContext(context, () -> approved("過期", "不要建立待辦"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "不應建立", null, TaskPriority.NORMAL);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-expired", source(approved), command));
        Instant expiredAt = Instant.now(clock).minusSeconds(3600);
        jdbc.update(
                """
                UPDATE knowledge_materialization
                SET created_at = ?, expires_at = ?
                WHERE id = ?
                """,
                java.sql.Timestamp.from(expiredAt.minusSeconds(3600)),
                java.sql.Timestamp.from(expiredAt),
                proposal.id());

        assertThatThrownBy(() -> inContext(context, () -> materializations.materialize(
                        new KnowledgeMaterializationConsent(
                                "confirm-expired",
                                proposal.id(),
                                proposal.revision(),
                                CHANNEL,
                                CONVERSATION_SCOPE,
                                true),
                        command)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM knowledge_materialization WHERE id = ?",
                        String.class,
                        proposal.id()))
                .isEqualTo("EXPIRED");
        assertThat(count("task")).isZero();
    }

    @Test
    void typedBindingCreatesOneLinkedTaskOnReplay() {
        WorkspaceContext context = seedContext("Task materialization");
        Approved approved = inContext(context, () -> approved("抵達", "回報平安"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "回報平安", NOW.plusSeconds(7200), TaskPriority.HIGH);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-task-once",
                        source(approved),
                        command));
        KnowledgeMaterializationConsent consent = new KnowledgeMaterializationConsent(
                "materialize-task-once",
                proposal.id(),
                proposal.revision(),
                CHANNEL,
                CONVERSATION_SCOPE,
                true);

        KnowledgeMaterializationView created =
                inContext(context, () -> materializations.materialize(consent, command));
        KnowledgeMaterializationView replay =
                inContext(context, () -> materializations.materialize(consent, command));

        assertThat(replay).isEqualTo(created);
        assertThat(created.targetKind())
                .isEqualTo(KnowledgeMaterializationView.TargetKind.TASK);
        assertThat(count("task")).isEqualTo(1L);
        assertThat(count("calendar_task_binding")).isEqualTo(1L);
        assertThat(count("knowledge_materialization")).isEqualTo(1L);
    }

    @Test
    void materializedChildRecordsOneLifeEventWhilePureProposalCancelAndReadRecordNone() {
        WorkspaceContext context = seedContext("Materialization LifeRecord");
        Approved approved = inContext(
                context,
                () -> approved("登船知識", "抵達後回報平安"));
        long baseline = lifeCount(context);

        assertThat(inContext(
                        context,
                        () -> reads.retrieve(
                                CalendarKnowledgeTarget.plan(approved.planId()),
                                "登船",
                                5)))
                .hasSize(1);
        KnowledgeMaterializationCommand.CreateTask canceledCommand =
                new KnowledgeMaterializationCommand.CreateTask(
                        "不應建立的待辦", null, TaskPriority.NORMAL);
        KnowledgeMaterializationProposalView canceledProposal = inContext(
                context,
                () -> materializations.prepare(
                        "prepare-life-cancel",
                        source(approved),
                        canceledCommand));
        inContext(
                context,
                () -> materializations.cancel(
                        "cancel-life-once",
                        canceledProposal.id(),
                        canceledProposal.revision(),
                        CHANNEL,
                        CONVERSATION_SCOPE));

        assertThat(lifeCount(context)).isEqualTo(baseline);
        assertThat(countLifeRecords(context, "不應建立的待辦", "TASK")).isZero();

        KnowledgeMaterializationCommand.CreateTask materializedCommand =
                new KnowledgeMaterializationCommand.CreateTask(
                        "回報平安", null, TaskPriority.NORMAL);
        KnowledgeMaterializationConsent consent = inContext(
                context,
                () -> consent(
                        "materialize-life-task",
                        approved,
                        materializedCommand));
        assertThat(lifeCount(context)).isEqualTo(baseline);

        KnowledgeMaterializationView created = inContext(
                context,
                () -> materializations.materialize(
                        consent, materializedCommand));
        KnowledgeMaterializationView replay = inContext(
                context,
                () -> materializations.materialize(
                        consent, materializedCommand));

        assertThat(replay).isEqualTo(created);
        assertThat(count("task")).isEqualTo(1L);
        assertThat(countLifeRecords(context, "回報平安", "TASK")).isEqualTo(1L);
        assertThat(lifeCount(context)).isEqualTo(baseline + 1L);
    }

    @Test
    void annotationBindingCanMaterializeOneLinkedTask() {
        WorkspaceContext context = seedContext("Annotation materialization");
        Approved approved = inContext(context, () -> approvedAnnotation(
                "登船附件", "出示電子船票"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "準備電子船票", null, TaskPriority.NORMAL);

        KnowledgeMaterializationView created = inContext(
                context,
                () -> materializations.materialize(
                        consent("materialize-annotation", approved, command), command));

        assertThat(created.targetKind())
                .isEqualTo(KnowledgeMaterializationView.TargetKind.TASK);
        assertThat(count("task")).isEqualTo(1L);
        assertThat(count("calendar_task_binding")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM knowledge_materialization
                        WHERE annotation_binding_id = ?
                          AND fact_binding_id IS NULL
                          AND status = 'COMPLETED'
                        """,
                        Long.class,
                        approved.binding().id()))
                .isEqualTo(1L);
    }

    @Test
    void concurrentConfirmationCreatesExactlyOneTargetAndReturnsSameResult()
            throws Exception {
        WorkspaceContext context = seedContext("Concurrent materialization");
        Approved approved = inContext(context, () -> approved("登船確認", "建立單一待辦"));
        KnowledgeMaterializationCommand.CreateTask command =
                new KnowledgeMaterializationCommand.CreateTask(
                        "單一待辦", null, TaskPriority.NORMAL);
        KnowledgeMaterializationProposalView proposal =
                inContext(context, () -> materializations.prepare(
                        "prepare-concurrent", source(approved), command));
        KnowledgeMaterializationConsent consent = new KnowledgeMaterializationConsent(
                "confirm-concurrent",
                proposal.id(),
                proposal.revision(),
                CHANNEL,
                CONVERSATION_SCOPE,
                true);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<KnowledgeMaterializationView> first = pool.submit(
                    () -> concurrentMaterialize(context, consent, command, ready, start));
            Future<KnowledgeMaterializationView> second = pool.submit(
                    () -> concurrentMaterialize(context, consent, command, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS))
                    .isEqualTo(second.get(30, TimeUnit.SECONDS));
            assertThat(count("task")).isEqualTo(1L);
            assertThat(count("calendar_task_binding")).isEqualTo(1L);
            assertThat(count("knowledge_materialization")).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void typedBindingCanMaterializeAbsoluteNodeAndPlanningPreference() {
        WorkspaceContext context = seedContext("Node preference materialization");
        Approved approved = inContext(context, () -> approved("轉乘", "多留四十分鐘"));

        var nodeCommand = new KnowledgeMaterializationCommand.CreateCalendarNode(
                        "transfer-buffer",
                        "轉乘緩衝終點",
                        NOW.plusSeconds(3600));
        var nodeConsent =
                inContext(context, () -> consent(
                        "materialize-node", approved, nodeCommand));
        long lifeRecordsBefore = count("tagged_life_record");
        var materializedNode = inContext(
                context,
                () -> materializations.materialize(nodeConsent, nodeCommand));
        var replayedNode = inContext(
                context,
                () -> materializations.materialize(nodeConsent, nodeCommand));
        assertThat(replayedNode).isEqualTo(materializedNode);

        inContext(context, () -> materializations.materialize(
                consent(
                        "materialize-preference",
                        approved,
                        new KnowledgeMaterializationCommand.SetPlanningPreference(40, 20)),
                new KnowledgeMaterializationCommand.SetPlanningPreference(40, 20)));

        assertThat(count("calendar_time_node")).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "SELECT extra_transfer_minutes FROM planning_preference",
                        Integer.class))
                .isEqualTo(40);
        assertThat(count("knowledge_materialization")).isEqualTo(2L);
        assertThat(count("tagged_life_record")).isEqualTo(lifeRecordsBefore + 1);
    }

    @Test
    void reminderMaterializationTargetsExistingTaskWithoutChangingDeadline() {
        WorkspaceContext context = seedContext("Reminder materialization");
        Approved approved = inContext(context, () -> approved("提醒", "出發前通知"));
        Instant testNow = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Instant deadline = testNow.plusSeconds(10800);
        Task task = inContext(context, () -> tasks.createTask(
                "準備船票", null, TaskPriority.NORMAL, deadline));
        inContext(context, () -> taskBindings.bind(
                "bind-reminder-task",
                task.getId(),
                CalendarTaskTarget.plan(approved.planId())));

        inContext(context, () -> materializations.materialize(
                consent(
                        "materialize-reminder",
                        approved,
                        new KnowledgeMaterializationCommand.CreateTaskReminder(
                                task.getId(), testNow.plusSeconds(1800))),
                new KnowledgeMaterializationCommand.CreateTaskReminder(
                        task.getId(), testNow.plusSeconds(1800))));

        assertThat(jdbc.queryForObject(
                                "SELECT due_at FROM task WHERE id = ?",
                                java.sql.Timestamp.class,
                                task.getId())
                        .toInstant())
                .isEqualTo(deadline);
        assertThat(count("task_reminder_rule")).isEqualTo(1L);
        assertThat(count("calendar_reminder_rule")).isZero();
    }

    private Approved approved(String title, String text) {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        UserKnowledgeFact fact = facts.remember(
                UserKnowledgeFact.Category.PLACE_GUIDANCE, title, text);
        CalendarKnowledgeBindingView binding = bindings.bindFact(
                "materialization-binding-" + planId,
                fact.getId(),
                CalendarKnowledgeTarget.plan(planId));
        return new Approved(planId, binding);
    }

    private Approved approvedAnnotation(String title, String text) {
        UUID planId = UUID.randomUUID();
        plans.saveAndFlush(CalendarPlanEntity.create(
                planId,
                title,
                CalendarPlacement.point(NOW, ZoneId.of("Asia/Taipei")),
                NOW));
        ObjectAnnotation annotation = annotations.saveAndFlush(ObjectAnnotation.create(
                ObjectAnnotation.TargetType.MEDIA,
                91L,
                title,
                text,
                NOW));
        CalendarKnowledgeBindingView binding = bindings.bindAnnotation(
                "materialization-annotation-binding-" + planId,
                annotation.getId(),
                CalendarKnowledgeTarget.plan(planId));
        return new Approved(planId, binding);
    }

    private KnowledgeMaterializationConsent consent(
            String key,
            Approved approved,
            KnowledgeMaterializationCommand command) {
        KnowledgeMaterializationProposalView proposal = materializations.prepare(
                "prepare-" + key, source(approved), command);
        return new KnowledgeMaterializationConsent(
                key,
                proposal.id(),
                proposal.revision(),
                CHANNEL,
                CONVERSATION_SCOPE,
                true);
    }

    private KnowledgeMaterializationSource source(Approved approved) {
        CalendarKnowledgeBindingView binding = approved.binding();
        return new KnowledgeMaterializationSource(
                binding.sourceKind(),
                binding.id(),
                binding.sourceUpdatedAt(),
                binding.revision(),
                CHANNEL,
                CONVERSATION_SCOPE);
    }

    private WorkspaceContext seedContext(String label) {
        UUID actor = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        seedUser(actor, label);
        jdbc.update(
                """
                INSERT INTO workspace (
                    id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, ?, 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                workspace,
                label,
                actor);
        return new WorkspaceContext(actor, workspace, WorkspaceChannel.TEST);
    }

    private void seedUser(UUID actorId, String label) {
        jdbc.update(
                """
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                actorId,
                label);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long lifeCount(WorkspaceContext context) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM tagged_life_record
                WHERE workspace_id = ? AND created_by_user_id = ?
                """,
                Long.class,
                context.workspaceId(),
                context.actorId());
    }

    private long countLifeRecords(
            WorkspaceContext context, String title, String recordType) {
        return jdbc.queryForObject(
                """
                SELECT count(*) FROM tagged_life_record
                WHERE workspace_id = ? AND created_by_user_id = ?
                  AND title = ? AND record_type = ?
                """,
                Long.class,
                context.workspaceId(),
                context.actorId(),
                title,
                recordType);
    }

    private <T> T inContext(WorkspaceContext context, Supplier<T> work) {
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            return work.get();
        }
    }

    private KnowledgeMaterializationView concurrentMaterialize(
            WorkspaceContext context,
            KnowledgeMaterializationConsent consent,
            KnowledgeMaterializationCommand command,
            CountDownLatch ready,
            CountDownLatch start)
            throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return inContext(
                context, () -> materializations.materialize(consent, command));
    }

    private record Approved(UUID planId, CalendarKnowledgeBindingView binding) {}
}
