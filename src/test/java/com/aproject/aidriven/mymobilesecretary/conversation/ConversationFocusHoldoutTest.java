package com.aproject.aidriven.mymobilesecretary.conversation;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusAtomicExecutor;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusQuoteResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
import com.aproject.aidriven.mymobilesecretary.conversation.application.FocusResponseEnvelope;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationFocus;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLog;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineMessageLogService;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentResult;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentService;
import com.aproject.aidriven.mymobilesecretary.shared.observability.RequestCorrelationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Evaluator-owned sealed Focus holdout. The fixture and oracle stay outside the repository and are never logged.
 */
@Tag("conversation-focus-holdout")
@EnabledIfSystemProperty(named = "conversation.focus.holdout.enabled", matches = "true")
class ConversationFocusHoldoutTest extends IntegrationTestBase {

    private static final String ARTIFACT_VERSION = "3";
    private static final String ORACLE_VERSION = "2";

    @Autowired private ObjectMapper objectMapper;
    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intentService;
    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationFocusQuoteResolver quoteResolver;
    @Autowired private ConversationFocusAtomicExecutor atomicExecutor;
    @Autowired private ConversationScopeResolver scopeResolver;
    @Autowired private LineMessageLogService lineMessageLogService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void capturesOrAssertsTheEvaluatorOwnedHoldoutWithoutLeakingItsContents() throws Exception {
        ConversationFocusHoldoutProtocol.Configuration configuration =
                ConversationFocusHoldoutProtocol.configuration(System.getProperties(), repositoryRoot());
        if (configuration.phase() == ConversationFocusHoldoutProtocol.Phase.CAPTURE) {
            capture(configuration);
            return;
        }
        assertCapture(configuration);
    }

    private void capture(ConversationFocusHoldoutProtocol.Configuration configuration) throws Exception {
        byte[] inputBytes = Files.readAllBytes(configuration.input());
        HoldoutInput input = mapper().readValue(inputBytes, HoldoutInput.class);
        validate(input);
        List<ScenarioCapture> scenarios = new ArrayList<>();
        for (ScenarioInput scenario : input.scenarios()) {
            scenarios.add(run(scenario));
        }
        CaptureArtifact artifact = new CaptureArtifact(ARTIFACT_VERSION, sha256(inputBytes), scenarios);
        byte[] artifactBytes = mapper().writeValueAsBytes(artifact);
        Files.write(configuration.capture(), artifactBytes);
        Files.writeString(sidecar(configuration.capture()), sha256(artifactBytes), StandardCharsets.UTF_8);
    }

    private void assertCapture(ConversationFocusHoldoutProtocol.Configuration configuration) throws Exception {
        byte[] captureBytes = Files.readAllBytes(configuration.capture());
        String actualCaptureHash = sha256(captureBytes);
        String sealedCaptureHash = Files.readString(sidecar(configuration.capture()), StandardCharsets.UTF_8).trim();
        if (!actualCaptureHash.equals(sealedCaptureHash)) {
            throw new AssertionError("holdout capture hash mismatch");
        }
        CaptureArtifact capture = mapper().readValue(captureBytes, CaptureArtifact.class);
        HoldoutOracle oracle = mapper().readValue(Files.readAllBytes(configuration.oracle()), HoldoutOracle.class);
        if (!ARTIFACT_VERSION.equals(capture.version())
                || !ORACLE_VERSION.equals(oracle.version())
                || !capture.inputSha256().equals(oracle.inputSha256())
                || !actualCaptureHash.equals(oracle.captureSha256())) {
            throw new AssertionError("holdout input or capture hash mismatch");
        }
        assertMatches(capture, oracle);
    }

    static void assertMatches(CaptureArtifact capture, HoldoutOracle oracle) {
        if (capture.scenarios().size() != oracle.scenarios().size()) {
            mismatch(0, 0, "scenarioCount");
        }
        for (int scenarioIndex = 0; scenarioIndex < capture.scenarios().size(); scenarioIndex++) {
            ScenarioCapture actualScenario = capture.scenarios().get(scenarioIndex);
            ScenarioOracle expectedScenario = oracle.scenarios().get(scenarioIndex);
            if (!actualScenario.id().equals(expectedScenario.id())) {
                mismatch(scenarioIndex + 1, 0, "scenarioId");
            }
            if (actualScenario.turns().size() != expectedScenario.turns().size()) {
                mismatch(scenarioIndex + 1, 0, "turnCount");
            }
            for (int turnIndex = 0; turnIndex < actualScenario.turns().size(); turnIndex++) {
                assertTurn(scenarioIndex + 1, turnIndex + 1,
                        actualScenario.turns().get(turnIndex),
                        expectedScenario.turns().get(turnIndex));
            }
        }
    }

    private static void assertTurn(int scenarioIndex, int turnIndex,
                                   TurnCapture actual, TurnOracle expected) {
        equal(scenarioIndex, turnIndex, "contextKey", actual.contextKey(), expected.contextKey());
        equal(scenarioIndex, turnIndex, "executionStatus",
                actual.executionStatus(), expected.executionStatus());
        equal(scenarioIndex, turnIndex, "typedAction", actual.typedAction(), expected.typedAction());
        equal(scenarioIndex, turnIndex, "activeFocusAlias",
                actual.activeFocusAlias(), expected.activeFocusAlias());
        equal(scenarioIndex, turnIndex, "activeFocusRoot",
                actual.activeFocusRoot(), expected.activeFocusRoot());
        equal(scenarioIndex, turnIndex, "scopeRevision",
                actual.scopeRevision(), expected.scopeRevision());
        equal(scenarioIndex, turnIndex, "taskCount", actual.taskCount(), expected.taskCount());
        equal(scenarioIndex, turnIndex, "itemCount", actual.itemCount(), expected.itemCount());
        equal(scenarioIndex, turnIndex, "scheduleCount",
                actual.scheduleCount(), expected.scheduleCount());
        equal(scenarioIndex, turnIndex, "focusTransitionCount",
                actual.focusTransitionCount(), expected.focusTransitionCount());
        equal(scenarioIndex, turnIndex, "privacySafe",
                actual.privacySafe(), expected.privacySafe());
        validateFacts(scenarioIndex, turnIndex, expected.requiredReplyFacts(), true);
        validateFacts(scenarioIndex, turnIndex, expected.forbiddenReplyFacts(), false);
        validateFacts(scenarioIndex, turnIndex, expected.requiredInterpretationFacts(), false);
        validateFacts(scenarioIndex, turnIndex, expected.forbiddenInterpretationFacts(), false);
        for (String fact : expected.requiredReplyFacts()) {
            if (!actual.publicReply().contains(fact)) {
                mismatch(scenarioIndex, turnIndex, "requiredReplyFacts");
            }
        }
        for (String fact : expected.forbiddenReplyFacts()) {
            if (actual.publicReply().contains(fact)) {
                mismatch(scenarioIndex, turnIndex, "forbiddenReplyFacts");
            }
        }
        for (String fact : expected.requiredInterpretationFacts()) {
            if (!actual.interpretationContext().contains(fact)) {
                mismatch(scenarioIndex, turnIndex, "requiredInterpretationFacts");
            }
        }
        for (String fact : expected.forbiddenInterpretationFacts()) {
            if (actual.interpretationContext().contains(fact)) {
                mismatch(scenarioIndex, turnIndex, "forbiddenInterpretationFacts");
            }
        }
    }

    private static void validateFacts(int scenarioIndex, int turnIndex,
                                      List<String> facts, boolean required) {
        if (facts == null || (required && facts.isEmpty())
                || facts.stream().anyMatch(fact -> fact == null || fact.isBlank())
                || facts.stream().distinct().count() != facts.size()) {
            mismatch(scenarioIndex, turnIndex,
                    required ? "requiredReplyFacts" : "forbiddenReplyFacts");
        }
    }

    private static void equal(int scenarioIndex, int turnIndex, String field,
                              Object actual, Object expected) {
        if (!java.util.Objects.equals(actual, expected)) {
            mismatch(scenarioIndex, turnIndex, field);
        }
    }

    private static void mismatch(int scenarioIndex, int turnIndex, String field) {
        throw new AssertionError("holdout oracle mismatch [scenarioIndex=" + scenarioIndex
                + ", turnIndex=" + turnIndex + ", field=" + field + "]");
    }

    private ScenarioCapture run(ScenarioInput scenario) {
        ScenarioRuntime runtime = prepare(scenario);
        List<TurnCapture> captures = new ArrayList<>();
        for (TurnInput turn : scenario.turns()) {
            TurnInput effective = turn.replayOf() == null
                    ? turn : runtime.turns().get(turn.replayOf());
            WorkspaceContext context = runtime.contexts().get(turn.contextKey()).context();
            String executionStatus = "SUCCESS";
            String typedAction = null;
            String publicReply = "";
            String interpretationContext = effective.text() == null ? "" : effective.text();
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
                if ("QUOTED_FOCUS".equals(turn.executionKind())) {
                    try {
                        var quoted = quoteResolver.resolveSuspendedFocus(
                                runtime.focusAliases().get(turn.quotedFocusAlias()));
                        FocusResponseEnvelope envelope = atomicExecutor.execute(
                                quoted.decision(), quoted.control(),
                                requestDigest(turn.requestId()), quoted.notice(),
                                () -> FocusResponseEnvelope.withoutNotice(
                                        "已依明確引用切換處理焦點。"));
                        typedAction = "QUOTED_FOCUS_RESOLVED";
                        publicReply = envelope.message();
                    } catch (IllegalStateException failure) {
                        if (!Set.of("quoted focus is unavailable",
                                "quoted focus target is unavailable")
                                .contains(failure.getMessage())) {
                            throw failure;
                        }
                        executionStatus = "SAFE_REJECTION";
                        typedAction = "QUOTED_FOCUS_UNAVAILABLE";
                    }
                } else {
                    try {
                        if (effective.quotedMessageKey() != null) {
                            interpretationContext = lineMessageLogService.contextualize(
                                    effective.text(), runtime.messageExternalIds()
                                            .get(effective.quotedMessageKey()));
                        }
                        interpreter.nextCommand(effective.command());
                        String finalContext = interpretationContext;
                        IntentResult result = RequestCorrelationContext.run(
                                uuid(effective.requestId(), "requestId"),
                                () -> intentService.handleWithContext(
                                        effective.text(), finalContext,
                                        context.channel().name(), () -> { }));
                        typedAction = result.action().name();
                        publicReply = result.responseEnvelope().message();
                    } catch (LineMessageLogService.QuotedMessageUnavailableException failure) {
                        executionStatus = "SAFE_REJECTION";
                        typedAction = "QUOTED_MESSAGE_UNAVAILABLE";
                    }
                }
                if (turn.saveActiveFocusAs() != null) {
                    ConversationFocus active = focusService.activeFocus()
                            .orElseThrow(() -> new IllegalStateException(
                                    "holdout turn did not create an active focus"));
                    runtime.focusAliases().put(turn.saveActiveFocusAs(), active.getId());
                }
                captures.add(captureTurn(scenario, runtime, turn.contextKey(), context,
                        executionStatus, typedAction, publicReply, interpretationContext));
            }
            runtime.turns().put(turn.id(), effective);
        }
        return new ScenarioCapture(scenario.id(), captures);
    }

    private static void validate(HoldoutInput input) {
        validateInput(input);
    }

    static void validateInput(HoldoutInput input) {
        if (input == null || input.version() != 2
                || input.scenarios() == null || input.scenarios().isEmpty()) {
            throw new IllegalArgumentException(
                    "holdout input must contain version 2 and at least one scenario");
        }
        requireUnique(input.scenarios().stream().map(ScenarioInput::id).toList(),
                "holdout scenario ids must be unique");
        input.scenarios().forEach(ConversationFocusHoldoutTest::validateScenario);
    }

    private static void validateScenario(ScenarioInput scenario) {
        required(scenario.id(), "scenario id");
        if (scenario.actors() == null || scenario.actors().isEmpty()
                || scenario.workspaces() == null || scenario.workspaces().isEmpty()
                || scenario.contexts() == null || scenario.contexts().isEmpty()
                || scenario.messageArtifacts() == null
                || scenario.turns() == null || scenario.turns().isEmpty()) {
            throw new IllegalArgumentException("holdout scenario definitions are incomplete");
        }
        requireUnique(scenario.actors().stream().map(ActorInput::key).toList(),
                "holdout actor keys must be unique");
        requireUnique(scenario.workspaces().stream().map(WorkspaceInput::key).toList(),
                "holdout workspace keys must be unique");
        requireUnique(scenario.contexts().stream().map(ContextInput::key).toList(),
                "holdout context keys must be unique");
        requireUnique(scenario.messageArtifacts().stream()
                        .map(MessageArtifactInput::key).toList(),
                "holdout message artifact keys must be unique");
        requireUnique(scenario.turns().stream().map(TurnInput::id).toList(),
                "holdout turn ids must be unique");
        validateScenarioReferences(scenario);
    }

    private static void validateScenarioReferences(ScenarioInput scenario) {
        Set<String> actors = new LinkedHashSet<>();
        scenario.actors().forEach(actor -> {
            actors.add(required(actor.key(), "actor key"));
            uuid(actor.id(), "actorId");
        });
        Map<String, WorkspaceInput> workspaces = new LinkedHashMap<>();
        for (WorkspaceInput workspace : scenario.workspaces()) {
            required(workspace.key(), "workspace key");
            uuid(workspace.id(), "workspaceId");
            if (!actors.contains(workspace.ownerActorKey())) {
                throw new IllegalArgumentException("holdout workspace owner is unavailable");
            }
            workspaces.put(workspace.key(), workspace);
        }
        Map<String, ContextInput> contexts = new LinkedHashMap<>();
        for (ContextInput context : scenario.contexts()) {
            required(context.key(), "context key");
            if (!actors.contains(context.actorKey())
                    || !workspaces.containsKey(context.workspaceKey())) {
                throw new IllegalArgumentException("holdout context identity is unavailable");
            }
            channel(context.channel());
            required(context.adapterNamespace(), "adapterNamespace");
            required(context.scopeToken(), "scopeToken");
            contexts.put(context.key(), context);
        }
        validateTurns(scenario.turns(), contexts,
                validateMessages(scenario.messageArtifacts(), contexts));
    }

    private static Map<String, MessageArtifactInput> validateMessages(
            List<MessageArtifactInput> artifacts, Map<String, ContextInput> contexts) {
        Map<String, MessageArtifactInput> messages = new LinkedHashMap<>();
        for (MessageArtifactInput message : artifacts) {
            ContextInput owner = contexts.get(message.contextKey());
            if (owner == null || channel(owner.channel()) != WorkspaceChannel.LINE) {
                throw new IllegalArgumentException(
                        "holdout message artifact requires LINE context");
            }
            required(message.content(), "message content");
            required(message.externalMessageId(), "externalMessageId");
            try {
                LineMessageLog.Direction.valueOf(required(message.direction(), "direction"));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("holdout message direction is invalid");
            }
            messages.put(message.key(), message);
        }
        return messages;
    }

    private static void validateTurns(
            List<TurnInput> turns, Map<String, ContextInput> contexts,
            Map<String, MessageArtifactInput> messages) {
        Map<String, TurnInput> seenTurns = new LinkedHashMap<>();
        Set<String> focusAliases = new LinkedHashSet<>();
        for (TurnInput turn : turns) {
            if (turn == null || !contexts.containsKey(turn.contextKey())) {
                throw new IllegalArgumentException("holdout turn context is unavailable");
            }
            required(turn.id(), "turn id");
            if (turn.replayOf() != null) {
                TurnInput original = seenTurns.get(turn.replayOf());
                if (original == null) {
                    throw new IllegalArgumentException(
                            "holdout replay target is unavailable");
                }
                if (turn.text() != null || turn.requestId() != null || turn.command() != null
                        || turn.quotedMessageKey() != null || turn.quotedFocusAlias() != null
                        || turn.saveActiveFocusAs() != null
                        || !turn.contextKey().equals(original.contextKey())) {
                    throw new IllegalArgumentException(
                            "holdout replay must not redefine its payload");
                }
                seenTurns.put(turn.id(), original);
                continue;
            }
            validateOriginalTurn(turn, contexts, messages, focusAliases);
            if (turn.saveActiveFocusAs() != null
                    && !focusAliases.add(required(
                            turn.saveActiveFocusAs(), "focus alias"))) {
                throw new IllegalArgumentException(
                        "holdout focus aliases must be unique");
            }
            seenTurns.put(turn.id(), turn);
        }
    }

    private static void validateOriginalTurn(
            TurnInput turn, Map<String, ContextInput> contexts,
            Map<String, MessageArtifactInput> messages, Set<String> focusAliases) {
        if ("INTENT".equals(turn.executionKind())) {
            validateIntentPayload(turn);
            if (turn.quotedMessageKey() != null
                    && (!messages.containsKey(turn.quotedMessageKey())
                    || channel(contexts.get(turn.contextKey()).channel())
                    != WorkspaceChannel.LINE)) {
                throw new IllegalArgumentException(
                        "holdout quoted message requires LINE context");
            }
            return;
        }
        validateQuotedFocusTurn(turn, focusAliases);
    }

    private static void validateIntentPayload(TurnInput turn) {
        if (turn.command() == null || turn.command().type() == null
                || turn.text() == null || turn.text().isBlank()
                || turn.requestId() == null
                || !turn.text().equals(turn.command().sourceText())
                || turn.quotedFocusAlias() != null) {
            throw new IllegalArgumentException(
                    "holdout intent turn must contain matching text, requestId and command sourceText");
        }
        uuid(turn.requestId(), "requestId");
    }

    private static void validateQuotedFocusTurn(
            TurnInput turn, Set<String> focusAliases) {
        if (!"QUOTED_FOCUS".equals(turn.executionKind())) {
            throw new IllegalArgumentException("holdout execution kind is invalid");
        }
        if (turn.command() != null || turn.text() != null
                || turn.quotedMessageKey() != null || turn.saveActiveFocusAs() != null
                || turn.requestId() == null) {
            throw new IllegalArgumentException(
                    "holdout quoted focus payload is invalid");
        }
        uuid(turn.requestId(), "requestId");
        if (!focusAliases.contains(turn.quotedFocusAlias())) {
            throw new IllegalArgumentException(
                    "holdout quoted focus alias is unavailable");
        }
    }

    private static void requireUnique(List<String> values, String message) {
        if (values.stream().anyMatch(value -> value == null || value.isBlank())
                || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException(message);
        }
    }

    private ScenarioRuntime prepare(ScenarioInput scenario) {
        Map<String, UUID> actors = new LinkedHashMap<>();
        for (ActorInput actor : scenario.actors()) {
            UUID actorId = uuid(actor.id(), "actorId");
            actors.put(actor.key(), actorId);
            seedActor(actorId);
        }
        Map<String, WorkspaceRuntime> workspaces = new LinkedHashMap<>();
        for (WorkspaceInput workspace : scenario.workspaces()) {
            UUID workspaceId = uuid(workspace.id(), "workspaceId");
            UUID ownerId = actors.get(workspace.ownerActorKey());
            workspaces.put(workspace.key(), new WorkspaceRuntime(workspaceId, ownerId));
            seedWorkspace(workspaceId, ownerId);
        }
        Map<String, ContextRuntime> contexts = new LinkedHashMap<>();
        Set<String> memberships = new LinkedHashSet<>();
        for (ContextInput input : scenario.contexts()) {
            UUID actorId = actors.get(input.actorKey());
            WorkspaceRuntime workspace = workspaces.get(input.workspaceKey());
            if (!actorId.equals(workspace.ownerActorId())
                    && memberships.add(input.actorKey() + "|" + input.workspaceKey())) {
                seedMember(actorId, workspace.workspaceId(), workspace.ownerActorId());
            }
            WorkspaceContext context = new WorkspaceContext(
                    actorId, workspace.workspaceId(), channel(input.channel()),
                    input.adapterNamespace(), input.scopeToken());
            contexts.put(input.key(), new ContextRuntime(context));
        }
        return new ScenarioRuntime(contexts, seedMessages(scenario, contexts),
                new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private Map<String, String> seedMessages(
            ScenarioInput scenario, Map<String, ContextRuntime> contexts) {
        Map<String, String> messageIds = new LinkedHashMap<>();
        for (MessageArtifactInput message : scenario.messageArtifacts()) {
            WorkspaceContext context = contexts.get(message.contextKey()).context();
            try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
                lineMessageLogService.recordSafely(
                        LineMessageLog.Direction.valueOf(message.direction()),
                        message.messageType(), message.content(),
                        message.externalMessageId(), null, message.referencePayload());
                String verified = lineMessageLogService.contextualize(
                        "holdout reference probe", message.externalMessageId());
                if (!verified.startsWith("【LINE 明確引用】")) {
                    throw new IllegalStateException(
                            "holdout message artifact was not persisted");
                }
            }
            messageIds.put(message.key(), message.externalMessageId());
        }
        return messageIds;
    }

    private TurnCapture captureTurn(
            ScenarioInput scenario, ScenarioRuntime runtime, String contextKey,
            WorkspaceContext context, String executionStatus, String typedAction,
            String publicReply, String interpretationContext) {
        return new TurnCapture(contextKey, executionStatus, typedAction, publicReply,
                interpretationContext, activeFocusAlias(runtime.focusAliases()),
                focusRoot(), scopeRevision(context),
                count("task", context.workspaceId()),
                count("item", context.workspaceId()),
                count("schedule_item", context.workspaceId()),
                count("focus_transition", context.workspaceId()),
                privacySafe(publicReply, scenario));
    }

    private String activeFocusAlias(Map<String, UUID> aliases) {
        UUID activeId = focusService.activeFocus()
                .map(ConversationFocus::getId).orElse(null);
        if (activeId == null) {
            return "NONE";
        }
        return aliases.entrySet().stream()
                .filter(entry -> entry.getValue().equals(activeId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("UNALIASED");
    }

    private static String requestDigest(String requestId) {
        return sha256(required(requestId, "requestId")
                .getBytes(StandardCharsets.UTF_8));
    }

    private String focusRoot() {
        return focusService.activeFocus().map(focus -> focus.getRootDomain()).orElse("NONE");
    }

    private long scopeRevision(WorkspaceContext context) {
        return jdbcTemplate.query("""
                SELECT revision FROM conversation_focus_head
                WHERE workspace_id = ? AND created_by_user_id = ? AND channel = ?
                  AND conversation_scope_digest = ?
                """, statement -> {
            statement.setObject(1, context.workspaceId());
            statement.setObject(2, context.actorId());
            statement.setString(3, context.channel().name());
            statement.setString(4, scopeResolver.current(context).digest());
        }, resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L);
    }

    private long count(String table, UUID workspaceId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE workspace_id = ?",
                Long.class, workspaceId);
    }

    private static boolean privacySafe(String reply, ScenarioInput scenario) {
        if (reply == null || reply.matches(
                "(?s).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                        + "[0-9a-f]{4}-[0-9a-f]{12}\\b.*")) {
            return false;
        }
        List<String> secrets = new ArrayList<>();
        scenario.actors().forEach(actor -> secrets.add(actor.id()));
        scenario.workspaces().forEach(workspace -> secrets.add(workspace.id()));
        scenario.contexts().forEach(context -> {
            secrets.add(context.scopeToken());
            secrets.add(context.adapterNamespace());
        });
        scenario.messageArtifacts().forEach(
                message -> secrets.add(message.externalMessageId()));
        return secrets.stream().filter(value -> value != null && !value.isBlank())
                .noneMatch(reply::contains);
    }

    private void seedActor(UUID actorId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'sealed holdout user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
    }

    private void seedWorkspace(UUID workspaceId, UUID ownerId) {
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'sealed holdout workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, ownerId);
    }

    private void seedMember(UUID actorId, UUID workspaceId, UUID ownerId) {
        jdbcTemplate.update("""
                INSERT INTO workspace_member (
                    id, workspace_id, user_id, role, created_by_user_id, joined_at, updated_at)
                VALUES (?, ?, ?, 'MEMBER', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), workspaceId, actorId, ownerId);
    }

    private ObjectMapper mapper() {
        return objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static Path repositoryRoot() {
        return Path.of(System.getProperty("user.dir"));
    }

    private static Path sidecar(Path capture) {
        return capture.resolveSibling(capture.getFileName() + ".sha256");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(required(value, field));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    private static WorkspaceChannel channel(String value) {
        try {
            return WorkspaceChannel.valueOf(required(value, "channel"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("channel must be a WorkspaceChannel");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    record HoldoutInput(int version, List<ScenarioInput> scenarios) {}

    record ScenarioInput(
            String id, List<ActorInput> actors, List<WorkspaceInput> workspaces,
            List<ContextInput> contexts, List<MessageArtifactInput> messageArtifacts,
            List<TurnInput> turns) {}

    record ActorInput(String key, String id) {}

    record WorkspaceInput(String key, String id, String ownerActorKey) {}

    record ContextInput(
            String key, String actorKey, String workspaceKey, String channel,
            String adapterNamespace, String scopeToken) {}

    record MessageArtifactInput(
            String key, String contextKey, String direction, String messageType,
            String content, String externalMessageId, String referencePayload) {}

    record TurnInput(
            String id, String executionKind, String contextKey, String text,
            String requestId, IntentCommand command, String quotedMessageKey,
            String replayOf, String saveActiveFocusAs, String quotedFocusAlias) {}

    record CaptureArtifact(String version, String inputSha256, List<ScenarioCapture> scenarios) {}

    record HoldoutOracle(String version, String inputSha256, String captureSha256,
                         List<ScenarioOracle> scenarios) {}

    record ScenarioOracle(String id, List<TurnOracle> turns) {}

    record TurnOracle(
            String contextKey, String executionStatus, String typedAction,
            List<String> requiredReplyFacts, List<String> forbiddenReplyFacts,
            List<String> requiredInterpretationFacts,
            List<String> forbiddenInterpretationFacts, String activeFocusAlias,
            String activeFocusRoot, long scopeRevision, long taskCount, long itemCount,
            long scheduleCount, long focusTransitionCount, boolean privacySafe) {}

    record ScenarioCapture(String id, List<TurnCapture> turns) {}

    record TurnCapture(
            String contextKey, String executionStatus, String typedAction,
            String publicReply, String interpretationContext, String activeFocusAlias,
            String activeFocusRoot, long scopeRevision, long taskCount, long itemCount,
            long scheduleCount, long focusTransitionCount, boolean privacySafe) {}

    record WorkspaceRuntime(UUID workspaceId, UUID ownerActorId) {}

    record ContextRuntime(WorkspaceContext context) {}

    record ScenarioRuntime(
            Map<String, ContextRuntime> contexts, Map<String, String> messageExternalIds,
            Map<String, UUID> focusAliases, Map<String, TurnInput> turns) {}
}
