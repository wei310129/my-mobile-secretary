package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.calendar.application.CalendarApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.persistence.CalendarTimeNodeEntity;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderApplicationService;
import com.aproject.aidriven.mymobilesecretary.calendar.reminder.CalendarReminderRuleView;
import com.aproject.aidriven.mymobilesecretary.calendar.task.CalendarTaskBindingService;
import com.aproject.aidriven.mymobilesecretary.calendar.task.CalendarTaskTarget;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.BufferRuleService;
import com.aproject.aidriven.mymobilesecretary.knowledge.application.PlanningPreferenceService;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.BufferRule;
import com.aproject.aidriven.mymobilesecretary.knowledge.domain.PlanningPreference;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskReminderRuleService;
import com.aproject.aidriven.mymobilesecretary.reminder.application.TaskService;
import com.aproject.aidriven.mymobilesecretary.reminder.domain.Task;
import com.aproject.aidriven.mymobilesecretary.shared.error.BusinessException;
import com.aproject.aidriven.mymobilesecretary.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class KnowledgeMaterializationService {

    private static final Duration PROPOSAL_TTL = Duration.ofMinutes(15);

    private final JdbcTemplate jdbc;
    private final TaskService tasks;
    private final CalendarTaskBindingService taskBindings;
    private final CalendarApplicationService calendars;
    private final PlanningPreferenceService preferences;
    private final BufferRuleService bufferRules;
    private final TaskReminderRuleService taskReminders;
    private final CalendarReminderApplicationService calendarReminders;
    private final KnowledgeMaterializationExpiryService expiry;
    private final Clock clock;

    public KnowledgeMaterializationService(
            JdbcTemplate jdbc,
            TaskService tasks,
            CalendarTaskBindingService taskBindings,
            CalendarApplicationService calendars,
            PlanningPreferenceService preferences,
            BufferRuleService bufferRules,
            TaskReminderRuleService taskReminders,
            CalendarReminderApplicationService calendarReminders,
            KnowledgeMaterializationExpiryService expiry,
            Clock clock) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.taskBindings = taskBindings;
        this.calendars = calendars;
        this.preferences = preferences;
        this.bufferRules = bufferRules;
        this.taskReminders = taskReminders;
        this.calendarReminders = calendarReminders;
        this.expiry = expiry;
        this.clock = clock;
    }

    public KnowledgeMaterializationProposalView prepare(
            String requestKey,
            KnowledgeMaterializationSource requestedSource,
            KnowledgeMaterializationCommand command) {
        if (requestedSource == null || command == null) {
            throw new IllegalArgumentException(
                    "typed source and typed command are required");
        }
        WorkspaceContext context = context();
        String requestHash = hash(requireRequestKey(requestKey));
        String snapshot = commandSnapshot(command);
        String commandHash = hash(snapshot);
        String scopeHash = hash(requestedSource.conversationScopeKey());
        String payloadHash = hash(
                requestedSource.sourceKind() + "|"
                        + requestedSource.bindingId() + "|"
                        + requestedSource.sourceUpdatedAt() + "|"
                        + requestedSource.bindingRevision() + "|"
                        + requestedSource.channel() + "|" + scopeHash + "|"
                        + commandHash);
        StoredProposal replay = findByProposalRequest(requestHash, context);
        if (replay != null) {
            requirePayload(replay.proposalPayloadHash(), payloadHash);
            return replay.proposalView();
        }

        Source source = currentSource(
                requestedSource.sourceKind(),
                requestedSource.bindingId(),
                context,
                true);
        requireRequestedSource(requestedSource, source);
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(PROPOSAL_TTL);
        jdbc.update(
                """
                INSERT INTO knowledge_materialization (
                    id, source_kind, fact_binding_id, annotation_binding_id,
                    plan_id, consented_binding_revision,
                    consented_source_updated_at, channel,
                    conversation_scope_hash, target_kind, status, row_revision,
                    command_snapshot, command_hash,
                    proposal_request_hash, proposal_payload_hash,
                    expires_at, created_at, updated_at,
                    workspace_id, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_CONFIRMATION', 1,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UUID.randomUUID(),
                source.sourceKind().name(),
                source.sourceKind() == CalendarKnowledgeBindingView.SourceKind.FACT
                        ? source.bindingId()
                        : null,
                source.sourceKind()
                                == CalendarKnowledgeBindingView.SourceKind.ANNOTATION
                        ? source.bindingId()
                        : null,
                source.planId(),
                source.bindingRevision(),
                Timestamp.from(source.sourceUpdatedAt()),
                requestedSource.channel(),
                scopeHash,
                targetKind(command).name(),
                snapshot,
                commandHash,
                requestHash,
                payloadHash,
                Timestamp.from(expiresAt),
                Timestamp.from(now),
                Timestamp.from(now),
                context.workspaceId(),
                context.actorId());
        StoredProposal result = findByProposalRequest(requestHash, context);
        if (result == null) {
            throw new IllegalStateException(
                    "Knowledge materialization proposal arbitration produced no row");
        }
        requirePayload(result.proposalPayloadHash(), payloadHash);
        return result.proposalView();
    }

    public KnowledgeMaterializationView materialize(
            KnowledgeMaterializationConsent consent,
            KnowledgeMaterializationCommand command) {
        if (consent == null || !consent.confirmed()) {
            throw new IllegalArgumentException(
                    "Explicit materialization confirmation is required");
        }
        if (consent.proposalRevision() <= 0 || command == null) {
            throw new IllegalArgumentException(
                    "proposal revision and typed command are required");
        }
        WorkspaceContext context = context();
        String channel = requireChannel(consent.channel());
        String scopeHash = hash(requireScope(consent.conversationScopeKey()));
        Instant now = Instant.now(clock);
        expiry.expireIfDue(
                consent.proposalId(),
                context.workspaceId(),
                context.actorId(),
                now);

        String resolutionHash = hash(requireRequestKey(consent.requestKey()));
        String snapshot = commandSnapshot(command);
        String commandHash = hash(snapshot);
        String resolutionPayload = hash(
                consent.proposalId() + "|"
                        + consent.proposalRevision() + "|"
                        + channel + "|" + scopeHash + "|" + commandHash);
        StoredProposal replay = findByResolutionRequest(resolutionHash, context);
        if (replay != null) {
            requirePayload(replay.resolutionPayloadHash(), resolutionPayload);
            return completedView(replay);
        }

        StoredProposal proposal = findById(consent.proposalId(), context, true);
        if (proposal == null) {
            throw new NotFoundException(
                    "Knowledge materialization proposal", "requested proposal");
        }
        requireScope(proposal, channel, scopeHash);
        if (proposal.status()
                == KnowledgeMaterializationProposalView.Status.COMPLETED) {
            if (!proposal.commandHash().equals(commandHash)
                    || !proposal.commandSnapshot().equals(snapshot)) {
                throw idempotencyConflict();
            }
            return completedView(proposal);
        }
        if (proposal.status()
                        != KnowledgeMaterializationProposalView.Status.PENDING_CONFIRMATION
                || proposal.revision() != consent.proposalRevision()) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_REVISION_CONFLICT",
                    "Materialization proposal changed or is no longer confirmable");
        }
        if (!now.isBefore(proposal.expiresAt())) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_EXPIRED",
                    "Materialization proposal expired without changing downstream state");
        }
        if (!proposal.commandHash().equals(commandHash)
                || !proposal.commandSnapshot().equals(snapshot)
                || proposal.targetKind() != targetKind(command)) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_COMMAND_MISMATCH",
                    "Confirmed command differs from the prepared proposal");
        }

        Source source = currentSource(
                proposal.sourceKind(),
                proposal.sourceBindingId(),
                context,
                true);
        requireConsentedSource(proposal, source);
        Result result =
                execute(proposal.id(), proposal.planId(), source, command, context);
        int changed = jdbc.update(
                """
                UPDATE knowledge_materialization
                SET status = 'COMPLETED', row_revision = row_revision + 1,
                    resolution_kind = 'CONFIRM',
                    resolution_request_hash = ?,
                    resolution_payload_hash = ?,
                    task_id = ?, calendar_task_binding_id = ?,
                    node_id = ?, planning_preference_id = ?,
                    task_reminder_rule_id = ?,
                    calendar_reminder_rule_id = ?,
                    buffer_rule_id = ?, buffer_rule_place_id = ?,
                    updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'PENDING_CONFIRMATION' AND row_revision = ?
                """,
                resolutionHash,
                resolutionPayload,
                result.taskId(),
                result.taskBindingId(),
                result.nodeId(),
                result.preferenceId(),
                result.taskReminderRuleId(),
                result.calendarReminderRuleId(),
                result.bufferRuleId(),
                result.bufferRulePlaceId(),
                Timestamp.from(now),
                proposal.id(),
                context.workspaceId(),
                context.actorId(),
                consent.proposalRevision());
        if (changed != 1) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_REVISION_CONFLICT",
                    "Materialization proposal changed during confirmation");
        }
        return completedView(findById(proposal.id(), context, false));
    }

    public KnowledgeMaterializationProposalView cancel(
            String requestKey,
            UUID proposalId,
            long expectedRevision,
            String requestedChannel,
            String conversationScopeKey) {
        if (expectedRevision <= 0) {
            throw new IllegalArgumentException("proposal revision must be positive");
        }
        WorkspaceContext context = context();
        String channel = requireChannel(requestedChannel);
        String scopeHash = hash(requireScope(conversationScopeKey));
        String resolutionHash = hash(requireRequestKey(requestKey));
        String payloadHash =
                hash(proposalId + "|" + expectedRevision + "|" + channel + "|" + scopeHash);
        StoredProposal replay = findByResolutionRequest(resolutionHash, context);
        if (replay != null) {
            requirePayload(replay.resolutionPayloadHash(), payloadHash);
            if (replay.status()
                    != KnowledgeMaterializationProposalView.Status.CANCELED) {
                throw idempotencyConflict();
            }
            return replay.proposalView();
        }
        StoredProposal proposal = findById(proposalId, context, true);
        if (proposal == null) {
            throw new NotFoundException(
                    "Knowledge materialization proposal", "requested proposal");
        }
        requireScope(proposal, channel, scopeHash);
        if (proposal.status() != KnowledgeMaterializationProposalView.Status.PENDING_CONFIRMATION
                || proposal.revision() != expectedRevision) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_REVISION_CONFLICT",
                    "Materialization proposal changed or is no longer cancelable");
        }
        Instant now = Instant.now(clock);
        int changed = jdbc.update(
                """
                UPDATE knowledge_materialization
                SET status = 'CANCELED', row_revision = row_revision + 1,
                    resolution_kind = 'CANCEL',
                    resolution_request_hash = ?,
                    resolution_payload_hash = ?, updated_at = ?
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                  AND status = 'PENDING_CONFIRMATION' AND row_revision = ?
                """,
                resolutionHash,
                payloadHash,
                Timestamp.from(now),
                proposalId,
                context.workspaceId(),
                context.actorId(),
                expectedRevision);
        if (changed != 1) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_REVISION_CONFLICT",
                    "Materialization proposal changed during cancellation");
        }
        return findById(proposalId, context, false).proposalView();
    }

    public KnowledgeMaterializationView confirmPending(
            String requestKey,
            String requestedChannel,
            String conversationScopeKey,
            UUID trustedQuotedProposalId) {
        WorkspaceContext context = context();
        String channel = requireChannel(requestedChannel);
        String scope = requireScope(conversationScopeKey);
        String scopeHash = hash(scope);
        String resolutionHash = hash(requireRequestKey(requestKey));
        StoredProposal replay = findByResolutionRequest(resolutionHash, context);
        if (replay != null) {
            requireScope(replay, channel, scopeHash);
            if (replay.status()
                    != KnowledgeMaterializationProposalView.Status.COMPLETED) {
                throw idempotencyConflict();
            }
            return materialize(
                    new KnowledgeMaterializationConsent(
                            requestKey,
                            replay.id(),
                            replay.revision() - 1,
                            channel,
                            scope,
                            true),
                    decodedCommand(replay));
        }
        StoredProposal proposal = uniquePending(
                context, channel, scopeHash, trustedQuotedProposalId, true);
        return materialize(
                new KnowledgeMaterializationConsent(
                        requestKey,
                        proposal.id(),
                        proposal.revision(),
                        channel,
                        scope,
                        true),
                decodedCommand(proposal));
    }

    public UUID uniquePendingReference(
            String requestedChannel, String conversationScopeKey) {
        WorkspaceContext context = context();
        String channel = requireChannel(requestedChannel);
        String scopeHash = hash(requireScope(conversationScopeKey));
        return uniquePending(context, channel, scopeHash, null, true).id();
    }

    public KnowledgeMaterializationProposalView cancelPending(
            String requestKey,
            String requestedChannel,
            String conversationScopeKey,
            UUID trustedQuotedProposalId) {
        WorkspaceContext context = context();
        String channel = requireChannel(requestedChannel);
        String scope = requireScope(conversationScopeKey);
        String scopeHash = hash(scope);
        String resolutionHash = hash(requireRequestKey(requestKey));
        StoredProposal replay = findByResolutionRequest(resolutionHash, context);
        if (replay != null) {
            requireScope(replay, channel, scopeHash);
            if (replay.status()
                    != KnowledgeMaterializationProposalView.Status.CANCELED) {
                throw idempotencyConflict();
            }
            return cancel(
                    requestKey,
                    replay.id(),
                    replay.revision() - 1,
                    channel,
                    scope);
        }
        StoredProposal proposal = uniquePending(
                context, channel, scopeHash, trustedQuotedProposalId, false);
        return cancel(
                requestKey,
                proposal.id(),
                proposal.revision(),
                channel,
                scope);
    }

    private StoredProposal uniquePending(
            WorkspaceContext context,
            String channel,
            String scopeHash,
            UUID trustedQuotedProposalId,
            boolean requireUnexpired) {
        List<UUID> ids;
        if (trustedQuotedProposalId != null) {
            ids = jdbc.queryForList(
                    """
                    SELECT id
                    FROM knowledge_materialization
                    WHERE id = ?
                      AND workspace_id = ? AND created_by_user_id = ?
                      AND channel = ? AND conversation_scope_hash = ?
                      AND status = 'PENDING_CONFIRMATION'
                    FOR UPDATE
                    """,
                    UUID.class,
                    trustedQuotedProposalId,
                    context.workspaceId(),
                    context.actorId(),
                    channel,
                    scopeHash);
        } else {
            String expiryPredicate = requireUnexpired ? "AND expires_at > ?" : "";
            Object[] arguments = requireUnexpired
                    ? new Object[] {
                        context.workspaceId(),
                        context.actorId(),
                        channel,
                        scopeHash,
                        Timestamp.from(Instant.now(clock))
                    }
                    : new Object[] {
                        context.workspaceId(),
                        context.actorId(),
                        channel,
                        scopeHash
                    };
            ids = jdbc.queryForList(
                    """
                    SELECT id
                    FROM knowledge_materialization
                    WHERE workspace_id = ? AND created_by_user_id = ?
                      AND channel = ? AND conversation_scope_hash = ?
                      AND status = 'PENDING_CONFIRMATION'
                      %s
                    ORDER BY created_at DESC, id
                    LIMIT 2
                    FOR UPDATE
                    """
                            .formatted(expiryPredicate),
                    UUID.class,
                    arguments);
        }
        if (ids.size() != 1) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_PENDING_NOT_UNIQUE",
                    "There is no unique pending materialization in this conversation");
        }
        StoredProposal proposal = findById(ids.getFirst(), context, false);
        if (proposal == null) {
            throw new NotFoundException(
                    "Knowledge materialization proposal", "requested proposal");
        }
        return proposal;
    }

    private static KnowledgeMaterializationCommand decodedCommand(
            StoredProposal proposal) {
        try {
            if (!hash(proposal.commandSnapshot()).equals(proposal.commandHash())) {
                throw new IllegalArgumentException("snapshot hash mismatch");
            }
            KnowledgeMaterializationCommand command =
                    KnowledgeMaterializationSnapshotCodec.decode(
                            proposal.commandSnapshot());
            if (targetKind(command) != proposal.targetKind()) {
                throw new IllegalArgumentException("snapshot target mismatch");
            }
            return command;
        } catch (RuntimeException invalid) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_SNAPSHOT_INVALID",
                    "Stored materialization command could not be verified");
        }
    }

    private Result execute(
            UUID proposalId,
            UUID planId,
            Source source,
            KnowledgeMaterializationCommand command,
            WorkspaceContext context) {
        return switch (command) {
            case KnowledgeMaterializationCommand.CreateTask create -> {
                Task task = tasks.createTask(
                        create.title(), null, create.priority(), create.deadline());
                taskBindings.bind(
                        "knowledge-materialization-task-binding-" + proposalId,
                        task.getId(),
                        target(source));
                UUID bindingId = jdbc.queryForObject(
                        """
                        SELECT id FROM calendar_task_binding
                        WHERE task_id = ? AND plan_id = ?
                          AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        UUID.class,
                        task.getId(),
                        planId,
                        context.workspaceId(),
                        context.actorId());
                yield Result.task(task.getId(), bindingId);
            }
            case KnowledgeMaterializationCommand.CreateCalendarNode create -> {
                CalendarTimeNodeEntity node = calendars.addAbsoluteNode(
                        planId, create.nodeKey(), create.label(), create.time());
                yield Result.node(node.getId());
            }
            case KnowledgeMaterializationCommand.SetPlanningPreference set -> {
                PlanningPreference preference = preferences.setBuffers(
                        set.transferMinutes(), set.mealMinutes());
                yield Result.preference(preference.getId());
            }
            case KnowledgeMaterializationCommand.SetBufferRule set -> {
                BufferRule rule = bufferRules.setExplicitBuffer(
                        set.placeId(),
                        set.bufferMinutes(),
                        set.expectedExplicitRevision());
                yield Result.bufferRule(rule.getId(), rule.getPlaceId());
            }
            case KnowledgeMaterializationCommand.CreateTaskReminder create -> {
                UUID bindingId =
                        requiredTaskBinding(create.taskId(), planId, context);
                taskReminders.create(
                        "knowledge-materialization-task-reminder-" + proposalId,
                        create.taskId(),
                        create.remindAt());
                UUID ruleId = jdbc.queryForObject(
                        """
                        SELECT id FROM task_reminder_rule
                        WHERE task_id = ? AND status = 'ACTIVE'
                          AND workspace_id = ? AND created_by_user_id = ?
                        """,
                        UUID.class,
                        create.taskId(),
                        context.workspaceId(),
                        context.actorId());
                yield Result.taskReminder(create.taskId(), bindingId, ruleId);
            }
            case KnowledgeMaterializationCommand.CreateCalendarReminder create -> {
                requireCalendarReminderTarget(create, planId, source, context);
                CalendarReminderRuleView rule = switch (create.timing()) {
                    case KnowledgeMaterializationCommand.CalendarReminderTiming.Relative
                            relative ->
                        calendarReminders.createPersonalRelativeForNode(
                                planId,
                                create.nodeId(),
                                create.expectedNodeRevision(),
                                relative.offset(),
                                create.deliveryMode(),
                                create.ackInterval(),
                                create.maxAlerts(),
                                create.preferredChannel());
                    case KnowledgeMaterializationCommand.CalendarReminderTiming.Absolute
                            absolute ->
                        calendarReminders.createPersonalAbsoluteForNode(
                                planId,
                                create.nodeId(),
                                create.expectedNodeRevision(),
                                absolute.fireAt(),
                                create.deliveryMode(),
                                create.ackInterval(),
                                create.maxAlerts(),
                                create.preferredChannel());
                };
                yield Result.calendarReminder(rule.ruleId(), create.nodeId());
            }
        };
    }

    private void requireCalendarReminderTarget(
            KnowledgeMaterializationCommand.CreateCalendarReminder command,
            UUID planId,
            Source source,
            WorkspaceContext context) {
        List<ReminderNode> nodes = jdbc.query(
                """
                SELECT activity_id, revision
                FROM calendar_time_node
                WHERE id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                FOR UPDATE
                """,
                (row, index) -> new ReminderNode(
                        row.getObject("activity_id", UUID.class),
                        row.getLong("revision")),
                command.nodeId(),
                planId,
                context.workspaceId(),
                context.actorId());
        if (nodes.size() != 1
                || nodes.getFirst().revision() != command.expectedNodeRevision()) {
            throw new NotFoundException("Calendar node", "requested node");
        }
        ReminderNode node = nodes.getFirst();
        boolean inSourceScope = switch (source.targetKind()) {
            case PLAN -> true;
            case ACTIVITY -> source.activityId().equals(node.activityId());
            case NODE -> source.nodeId().equals(command.nodeId());
        };
        if (!inSourceScope) {
            throw new NotFoundException("Calendar node", "requested node");
        }
    }

    private UUID requiredTaskBinding(
            long taskId, UUID planId, WorkspaceContext context) {
        List<UUID> rows = jdbc.query(
                """
                SELECT id FROM calendar_task_binding
                WHERE task_id = ? AND plan_id = ?
                  AND workspace_id = ? AND created_by_user_id = ?
                """,
                (row, index) -> row.getObject("id", UUID.class),
                taskId,
                planId,
                context.workspaceId(),
                context.actorId());
        if (rows.size() != 1) {
            throw new NotFoundException(
                    "Calendar task binding", "task linked to knowledge plan");
        }
        return rows.getFirst();
    }

    private Source currentSource(
            CalendarKnowledgeBindingView.SourceKind kind,
            UUID bindingId,
            WorkspaceContext context,
            boolean lock) {
        String bindingTable = kind == CalendarKnowledgeBindingView.SourceKind.FACT
                ? "calendar_knowledge_fact_binding"
                : "calendar_knowledge_annotation_binding";
        String sourceTable = kind == CalendarKnowledgeBindingView.SourceKind.FACT
                ? "user_knowledge_fact"
                : "object_annotation";
        String sourceColumn = kind == CalendarKnowledgeBindingView.SourceKind.FACT
                ? "fact_id"
                : "annotation_id";
        List<LockedBinding> bindingRows = jdbc.query(
                """
                SELECT binding.id AS binding_id, binding.plan_id,
                       binding.binding_revision, binding.source_updated_at,
                       binding.status AS binding_status,
                       binding.review_state, binding.target_kind,
                       binding.activity_id, binding.node_id,
                       binding.%s AS source_id
                FROM %s binding
                WHERE binding.id = ? AND binding.workspace_id = ?
                  AND binding.created_by_user_id = ?
                %s
                """
                        .formatted(
                                sourceColumn,
                                bindingTable,
                                lock ? "FOR UPDATE" : ""),
                (row, index) -> new LockedBinding(
                        row.getObject("binding_id", UUID.class),
                        row.getObject("plan_id", UUID.class),
                        row.getLong("binding_revision"),
                        row.getTimestamp("source_updated_at").toInstant(),
                        row.getString("binding_status"),
                        row.getString("review_state"),
                        CalendarKnowledgeTarget.TargetKind.valueOf(
                                row.getString("target_kind")),
                        row.getObject("activity_id", UUID.class),
                        row.getObject("node_id", UUID.class),
                        row.getLong("source_id")),
                bindingId,
                context.workspaceId(),
                context.actorId());
        if (bindingRows.size() != 1) {
            throw new NotFoundException(
                    "Calendar knowledge binding", "requested binding");
        }
        LockedBinding binding = bindingRows.getFirst();
        String archiveProjection = kind
                        == CalendarKnowledgeBindingView.SourceKind.ANNOTATION
                ? "archived_at"
                : "NULL::timestamptz";
        List<LockedSource> sourceRows = jdbc.query(
                """
                SELECT updated_at, %s AS archived_at
                FROM %s
                WHERE id = ? AND workspace_id = ? AND created_by_user_id = ?
                %s
                """
                        .formatted(
                                archiveProjection,
                                sourceTable,
                                lock ? "FOR UPDATE" : ""),
                (row, index) -> {
                    Timestamp archived = row.getTimestamp("archived_at");
                    return new LockedSource(
                            row.getTimestamp("updated_at").toInstant(),
                            archived == null ? null : archived.toInstant());
                },
                binding.sourceId(),
                context.workspaceId(),
                context.actorId());
        if (sourceRows.size() != 1) {
            throw new NotFoundException(
                    "Calendar knowledge source", "requested source");
        }
        LockedSource lockedSource = sourceRows.getFirst();
        Source source = new Source(
                kind,
                binding.bindingId(),
                binding.planId(),
                binding.bindingRevision(),
                binding.sourceUpdatedAt(),
                lockedSource.updatedAt(),
                lockedSource.archivedAt(),
                binding.targetKind(),
                binding.activityId(),
                binding.nodeId(),
                binding.status(),
                binding.reviewState());
        if (!"ACTIVE".equals(source.bindingStatus())
                || source.sourceArchivedAt() != null) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_SOURCE_ARCHIVED",
                    "Knowledge source is no longer active");
        }
        if (!"CURRENT".equals(source.reviewState())) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_SOURCE_REVIEW_REQUIRED",
                    "Knowledge source must be reviewed before materialization");
        }
        if (!source.sourceUpdatedAt().equals(source.actualSourceUpdatedAt())) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_SOURCE_CHANGED",
                    "Knowledge source changed before the binding was reviewed");
        }
        return source;
    }

    private static void requireRequestedSource(
            KnowledgeMaterializationSource requested, Source actual) {
        if (requested.bindingRevision() != actual.bindingRevision()
                || !requested.sourceUpdatedAt().equals(actual.sourceUpdatedAt())) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_SOURCE_CHANGED",
                    "Knowledge source changed before proposal preparation");
        }
    }

    private static void requireConsentedSource(
            StoredProposal proposal, Source actual) {
        if (!proposal.planId().equals(actual.planId())
                || proposal.consentedBindingRevision() != actual.bindingRevision()
                || !proposal.consentedSourceUpdatedAt()
                        .equals(actual.sourceUpdatedAt())) {
            throw new BusinessException(
                    "KNOWLEDGE_MATERIALIZATION_SOURCE_CHANGED",
                    "Knowledge source changed before confirmation");
        }
    }

    private static CalendarTaskTarget target(Source source) {
        return switch (source.targetKind()) {
            case PLAN -> CalendarTaskTarget.plan(source.planId());
            case ACTIVITY ->
                    CalendarTaskTarget.activity(source.planId(), source.activityId());
            case NODE -> CalendarTaskTarget.node(source.planId(), source.nodeId());
        };
    }

    private StoredProposal findByProposalRequest(
            String requestHash, WorkspaceContext context) {
        return one(
                """
                proposal_request_hash = ?
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                false,
                requestHash,
                context.workspaceId(),
                context.actorId());
    }

    private StoredProposal findByResolutionRequest(
            String requestHash, WorkspaceContext context) {
        return one(
                """
                resolution_request_hash = ?
                AND workspace_id = ? AND created_by_user_id = ?
                """,
                false,
                requestHash,
                context.workspaceId(),
                context.actorId());
    }

    private StoredProposal findById(
            UUID id, WorkspaceContext context, boolean lock) {
        return one(
                """
                id = ? AND workspace_id = ? AND created_by_user_id = ?
                """,
                lock,
                id,
                context.workspaceId(),
                context.actorId());
    }

    private StoredProposal one(
            String predicate, boolean lock, Object... arguments) {
        List<StoredProposal> rows = jdbc.query(
                """
                SELECT id, source_kind,
                       COALESCE(fact_binding_id, annotation_binding_id)
                           AS source_binding_id,
                       plan_id, consented_binding_revision,
                       consented_source_updated_at, channel,
                       conversation_scope_hash, target_kind, status,
                       row_revision, command_snapshot, command_hash,
                       proposal_request_hash, proposal_payload_hash,
                       resolution_request_hash, resolution_payload_hash,
                       task_id, calendar_task_binding_id, node_id,
                       planning_preference_id, task_reminder_rule_id,
                       calendar_reminder_rule_id,
                       buffer_rule_id, buffer_rule_place_id, expires_at
                FROM knowledge_materialization
                WHERE %s
                %s
                """
                        .formatted(predicate, lock ? "FOR UPDATE" : ""),
                KnowledgeMaterializationService::proposal,
                arguments);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static StoredProposal proposal(ResultSet row, int index)
            throws SQLException {
        return new StoredProposal(
                row.getObject("id", UUID.class),
                CalendarKnowledgeBindingView.SourceKind.valueOf(
                        row.getString("source_kind")),
                row.getObject("source_binding_id", UUID.class),
                row.getObject("plan_id", UUID.class),
                row.getLong("consented_binding_revision"),
                row.getTimestamp("consented_source_updated_at").toInstant(),
                row.getString("channel"),
                row.getString("conversation_scope_hash"),
                KnowledgeMaterializationView.TargetKind.valueOf(
                        row.getString("target_kind")),
                KnowledgeMaterializationProposalView.Status.valueOf(
                        row.getString("status")),
                row.getLong("row_revision"),
                row.getString("command_snapshot"),
                row.getString("command_hash"),
                row.getString("proposal_request_hash"),
                row.getString("proposal_payload_hash"),
                row.getString("resolution_request_hash"),
                row.getString("resolution_payload_hash"),
                (Long) row.getObject("task_id"),
                row.getObject("calendar_task_binding_id", UUID.class),
                row.getObject("node_id", UUID.class),
                (Integer) row.getObject("planning_preference_id"),
                row.getObject("task_reminder_rule_id", UUID.class),
                row.getObject("calendar_reminder_rule_id", UUID.class),
                (Long) row.getObject("buffer_rule_id"),
                (Long) row.getObject("buffer_rule_place_id"),
                row.getTimestamp("expires_at").toInstant());
    }

    private static KnowledgeMaterializationView completedView(
            StoredProposal stored) {
        if (stored == null
                || stored.status()
                        != KnowledgeMaterializationProposalView.Status.COMPLETED) {
            throw new IllegalStateException(
                    "Knowledge materialization has no completed result");
        }
        String reference = switch (stored.targetKind()) {
            case TASK -> Long.toString(stored.taskId());
            case CALENDAR_NODE -> stored.nodeId().toString();
            case PLANNING_PREFERENCE ->
                    Integer.toString(stored.planningPreferenceId());
            case BUFFER_RULE -> Long.toString(stored.bufferRuleId());
            case TASK_REMINDER -> stored.taskReminderRuleId().toString();
            case CALENDAR_REMINDER -> stored.calendarReminderRuleId().toString();
        };
        return new KnowledgeMaterializationView(
                stored.id(),
                stored.sourceKind(),
                stored.sourceBindingId(),
                stored.targetKind(),
                reference);
    }

    private static KnowledgeMaterializationView.TargetKind targetKind(
            KnowledgeMaterializationCommand command) {
        return switch (command) {
            case KnowledgeMaterializationCommand.CreateTask ignored ->
                    KnowledgeMaterializationView.TargetKind.TASK;
            case KnowledgeMaterializationCommand.CreateCalendarNode ignored ->
                    KnowledgeMaterializationView.TargetKind.CALENDAR_NODE;
            case KnowledgeMaterializationCommand.SetPlanningPreference ignored ->
                    KnowledgeMaterializationView.TargetKind.PLANNING_PREFERENCE;
            case KnowledgeMaterializationCommand.SetBufferRule ignored ->
                    KnowledgeMaterializationView.TargetKind.BUFFER_RULE;
            case KnowledgeMaterializationCommand.CreateTaskReminder ignored ->
                    KnowledgeMaterializationView.TargetKind.TASK_REMINDER;
            case KnowledgeMaterializationCommand.CreateCalendarReminder ignored ->
                    KnowledgeMaterializationView.TargetKind.CALENDAR_REMINDER;
        };
    }

    private static String commandSnapshot(
            KnowledgeMaterializationCommand command) {
        return KnowledgeMaterializationSnapshotCodec.encode(command);
    }

    private static void requireScope(
            StoredProposal proposal, String channel, String scopeHash) {
        if (!proposal.channel().equals(channel)
                || !proposal.conversationScopeHash().equals(scopeHash)) {
            throw new NotFoundException(
                    "Knowledge materialization proposal", "requested proposal");
        }
    }

    private static void requirePayload(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw idempotencyConflict();
        }
    }

    private static BusinessException idempotencyConflict() {
        return new BusinessException(
                "IDEMPOTENCY_CONFLICT",
                "The request key was already used for another materialization");
    }

    private static WorkspaceContext context() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Knowledge materialization requires tenant scope");
        }
        return context;
    }

    private static String requireRequestKey(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 160) {
            throw new IllegalArgumentException("A bounded request key is required");
        }
        return value.strip();
    }

    private static String requireChannel(String value) {
        String channel = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!channel.matches("[A-Z][A-Z0-9_]{0,29}")) {
            throw new IllegalArgumentException("channel must be a stable identifier");
        }
        return channel;
    }

    private static String requireScope(String value) {
        if (value == null || value.isBlank() || value.strip().length() > 240) {
            throw new IllegalArgumentException(
                    "conversation scope must be non-blank and bounded");
        }
        return value.strip();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Source(
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            UUID bindingId,
            UUID planId,
            long bindingRevision,
            Instant sourceUpdatedAt,
            Instant actualSourceUpdatedAt,
            Instant sourceArchivedAt,
            CalendarKnowledgeTarget.TargetKind targetKind,
            UUID activityId,
            UUID nodeId,
            String bindingStatus,
            String reviewState) {
    }

    private record LockedBinding(
            UUID bindingId,
            UUID planId,
            long bindingRevision,
            Instant sourceUpdatedAt,
            String status,
            String reviewState,
            CalendarKnowledgeTarget.TargetKind targetKind,
            UUID activityId,
            UUID nodeId,
            long sourceId) {
    }

    private record LockedSource(Instant updatedAt, Instant archivedAt) {
    }

    private record Result(
            Long taskId,
            UUID taskBindingId,
            UUID nodeId,
            Integer preferenceId,
            UUID taskReminderRuleId,
            UUID calendarReminderRuleId,
            Long bufferRuleId,
            Long bufferRulePlaceId) {

        static Result task(long taskId, UUID bindingId) {
            return new Result(
                    taskId, bindingId, null, null, null, null, null, null);
        }

        static Result node(UUID nodeId) {
            return new Result(null, null, nodeId, null, null, null, null, null);
        }

        static Result preference(int preferenceId) {
            return new Result(
                    null, null, null, preferenceId, null, null, null, null);
        }

        static Result bufferRule(long ruleId, long placeId) {
            return new Result(
                    null, null, null, null, null, null, ruleId, placeId);
        }

        static Result taskReminder(long taskId, UUID bindingId, UUID ruleId) {
            return new Result(
                    taskId, bindingId, null, null, ruleId, null, null, null);
        }

        static Result calendarReminder(UUID ruleId, UUID nodeId) {
            return new Result(
                    null, null, nodeId, null, null, ruleId, null, null);
        }
    }

    private record ReminderNode(UUID activityId, long revision) {}

    private record StoredProposal(
            UUID id,
            CalendarKnowledgeBindingView.SourceKind sourceKind,
            UUID sourceBindingId,
            UUID planId,
            long consentedBindingRevision,
            Instant consentedSourceUpdatedAt,
            String channel,
            String conversationScopeHash,
            KnowledgeMaterializationView.TargetKind targetKind,
            KnowledgeMaterializationProposalView.Status status,
            long revision,
            String commandSnapshot,
            String commandHash,
            String proposalRequestHash,
            String proposalPayloadHash,
            String resolutionRequestHash,
            String resolutionPayloadHash,
            Long taskId,
            UUID taskBindingId,
            UUID nodeId,
            Integer planningPreferenceId,
            UUID taskReminderRuleId,
            UUID calendarReminderRuleId,
            Long bufferRuleId,
            Long bufferRulePlaceId,
            Instant expiresAt) {

        KnowledgeMaterializationProposalView proposalView() {
            return new KnowledgeMaterializationProposalView(
                    id,
                    sourceKind,
                    sourceBindingId,
                    targetKind,
                    status,
                    revision,
                    expiresAt);
        }
    }
}
