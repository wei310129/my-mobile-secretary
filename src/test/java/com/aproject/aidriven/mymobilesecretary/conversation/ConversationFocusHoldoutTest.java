package com.aproject.aidriven.mymobilesecretary.conversation;

import com.aproject.aidriven.mymobilesecretary.IntegrationTestBase;
import com.aproject.aidriven.mymobilesecretary.TestcontainersConfiguration.StubIntentInterpreter;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationFocusService;
import com.aproject.aidriven.mymobilesecretary.conversation.application.ConversationScopeResolver;
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
import java.util.List;
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

    private static final String ARTIFACT_VERSION = "2";
    private static final String ORACLE_VERSION = "1";

    @Autowired private ObjectMapper objectMapper;
    @Autowired private StubIntentInterpreter interpreter;
    @Autowired private IntentService intentService;
    @Autowired private ConversationFocusService focusService;
    @Autowired private ConversationScopeResolver scopeResolver;
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
        equal(scenarioIndex, turnIndex, "typedAction", actual.typedAction(), expected.typedAction());
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
        UUID actorId = uuid(scenario.actorId(), "actorId");
        UUID workspaceId = uuid(scenario.workspaceId(), "workspaceId");
        WorkspaceChannel channel = channel(scenario.channel());
        WorkspaceContext context = new WorkspaceContext(actorId, workspaceId, channel,
                required(scenario.adapterNamespace(), "adapterNamespace"),
                required(scenario.scopeToken(), "scopeToken"));
        seedAccount(actorId, workspaceId);
        List<TurnCapture> turns = new ArrayList<>();
        try (WorkspaceContextHolder.Scope ignored = WorkspaceContextHolder.open(context)) {
            for (TurnInput turn : scenario.turns()) {
                validate(turn);
                interpreter.nextCommand(turn.command());
                IntentResult result = RequestCorrelationContext.run(uuid(turn.requestId(), "requestId"),
                        () -> intentService.handle(turn.text(), channel.name()));
                String publicReply = result.responseEnvelope().message();
                turns.add(new TurnCapture(result.action().name(), publicReply, focusRoot(), scopeRevision(context),
                        count("task", workspaceId), count("item", workspaceId),
                        count("schedule_item", workspaceId), count("focus_transition", workspaceId),
                        privacySafe(publicReply, context)));
            }
        }
        return new ScenarioCapture(scenario.id(), turns);
    }

    private void validate(HoldoutInput input) {
        if (input == null || input.version() != 1 || input.scenarios() == null || input.scenarios().isEmpty()) {
            throw new IllegalArgumentException("holdout input must contain version 1 and at least one scenario");
        }
        long uniqueIds = input.scenarios().stream().map(ScenarioInput::id).distinct().count();
        if (uniqueIds != input.scenarios().size()) {
            throw new IllegalArgumentException("holdout scenario ids must be unique");
        }
    }

    private void validate(TurnInput turn) {
        if (turn == null || turn.command() == null || turn.command().type() == null
                || turn.text() == null || turn.text().isBlank() || turn.requestId() == null
                || !turn.text().equals(turn.command().sourceText())) {
            throw new IllegalArgumentException("holdout turn must contain matching text, requestId and command sourceText");
        }
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

    private static boolean privacySafe(String reply, WorkspaceContext context) {
        return reply != null && !reply.contains(context.actorId().toString())
                && !reply.contains(context.workspaceId().toString())
                && !reply.contains(context.conversationScopeToken())
                && !reply.matches("(?s).*\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b.*");
    }

    private void seedAccount(UUID actorId, UUID workspaceId) {
        jdbcTemplate.update("""
                INSERT INTO app_user (id, display_name, status, created_at, updated_at)
                VALUES (?, 'sealed holdout user', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, actorId);
        jdbcTemplate.update("""
                INSERT INTO workspace (id, name, type, created_by_user_id, created_at, updated_at)
                VALUES (?, 'sealed holdout workspace', 'PERSONAL', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, workspaceId, actorId);
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

    record ScenarioInput(String id, String actorId, String workspaceId, String channel,
                         String adapterNamespace, String scopeToken, List<TurnInput> turns) {}

    record TurnInput(String text, String requestId, IntentCommand command) {}

    record CaptureArtifact(String version, String inputSha256, List<ScenarioCapture> scenarios) {}

    record HoldoutOracle(String version, String inputSha256, String captureSha256,
                         List<ScenarioOracle> scenarios) {}

    record ScenarioOracle(String id, List<TurnOracle> turns) {}

    record TurnOracle(String typedAction, List<String> requiredReplyFacts,
                      List<String> forbiddenReplyFacts, String activeFocusRoot,
                      long scopeRevision, long taskCount, long itemCount, long scheduleCount,
                      long focusTransitionCount, boolean privacySafe) {}

    record ScenarioCapture(String id, List<TurnCapture> turns) {}

    record TurnCapture(String typedAction, String publicReply, String activeFocusRoot, long scopeRevision,
                       long taskCount, long itemCount, long scheduleCount, long focusTransitionCount,
                       boolean privacySafe) {}
}
