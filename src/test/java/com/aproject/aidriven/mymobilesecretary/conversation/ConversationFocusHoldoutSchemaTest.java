package com.aproject.aidriven.mymobilesecretary.conversation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.intent.application.IntentCommand;
import com.aproject.aidriven.mymobilesecretary.intent.application.IntentOptions;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationFocusHoldoutSchemaTest {

    @Test
    void acceptsNamedContextsMessagesFocusAliasesAndActualReplay() {
        var scenario = scenario(List.of(
                intentTurn("create", "owner", "create", null, null, "original"),
                intentTurn("quoted", "owner", "quoted", "quoted-message", null, null),
                quotedFocusTurn("resume", "peer", "original"),
                replayTurn("replay", "owner", "create")));

        assertThatCode(() -> ConversationFocusHoldoutTest.validateInput(
                new ConversationFocusHoldoutTest.HoldoutInput(2, List.of(scenario))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownContextAndForwardFocusAliasWithoutRevealingValues() {
        var unknownContext = scenario(List.of(
                intentTurn("create", "missing", "create", null, null, "original")));
        assertThatThrownBy(() -> ConversationFocusHoldoutTest.validateInput(
                new ConversationFocusHoldoutTest.HoldoutInput(2, List.of(unknownContext))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("holdout turn context is unavailable");

        var forwardAlias = scenario(List.of(
                quotedFocusTurn("resume", "owner", "later"),
                intentTurn("create", "owner", "create", null, null, "later")));
        assertThatThrownBy(() -> ConversationFocusHoldoutTest.validateInput(
                new ConversationFocusHoldoutTest.HoldoutInput(2, List.of(forwardAlias))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("holdout quoted focus alias is unavailable");
    }

    @Test
    void rejectsMixedReplayPayloadAndCrossChannelQuotedMessage() {
        var mixedReplay = scenario(List.of(
                intentTurn("create", "owner", "create", null, null, "original"),
                new ConversationFocusHoldoutTest.TurnInput(
                        "replay", "INTENT", "owner", "must be absent",
                        "00000000-0000-0000-0000-000000000099",
                        command("must be absent"), null, "create", null, null)));
        assertThatThrownBy(() -> ConversationFocusHoldoutTest.validateInput(
                new ConversationFocusHoldoutTest.HoldoutInput(2, List.of(mixedReplay))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("holdout replay must not redefine its payload");

        var nonLineQuote = new ConversationFocusHoldoutTest.ScenarioInput(
                "scenario",
                actors(),
                workspaces(),
                List.of(context("owner", "owner", "workspace", "REST"),
                        context("peer", "peer", "workspace", "TEST"),
                        context("line-owner", "owner", "workspace", "LINE")),
                List.of(message("line-owner")),
                List.of(intentTurn("quoted", "owner", "quoted", "quoted-message", null, null)));
        assertThatThrownBy(() -> ConversationFocusHoldoutTest.validateInput(
                new ConversationFocusHoldoutTest.HoldoutInput(2, List.of(nonLineQuote))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("holdout quoted message requires LINE context");
    }

    @Test
    void rejectsASecondPersonalWorkspaceBeforeDatabaseSetup() {
        var secondPersonal = new ConversationFocusHoldoutTest.WorkspaceInput(
                "other-workspace", "00000000-0000-0000-0000-000000000011",
                "owner", "PERSONAL");
        var invalid = new ConversationFocusHoldoutTest.ScenarioInput(
                "scenario", actors(),
                List.of(workspaces().getFirst(), secondPersonal),
                List.of(context("owner", "owner", "workspace", "LINE")),
                List.of(message()), List.of(
                        intentTurn("create", "owner", "create", null, null, "original")));

        assertThatThrownBy(() -> ConversationFocusHoldoutTest.validateInput(
                new ConversationFocusHoldoutTest.HoldoutInput(2, List.of(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("holdout actor may own only one personal workspace");
    }

    private static ConversationFocusHoldoutTest.ScenarioInput scenario(
            List<ConversationFocusHoldoutTest.TurnInput> turns) {
        return new ConversationFocusHoldoutTest.ScenarioInput(
                "scenario", actors(), workspaces(),
                List.of(context("owner", "owner", "workspace", "LINE"),
                        context("peer", "peer", "workspace", "TEST")),
                List.of(message()), turns);
    }

    private static List<ConversationFocusHoldoutTest.ActorInput> actors() {
        return List.of(
                new ConversationFocusHoldoutTest.ActorInput(
                        "owner", "00000000-0000-0000-0000-000000000001"),
                new ConversationFocusHoldoutTest.ActorInput(
                        "peer", "00000000-0000-0000-0000-000000000002"));
    }

    private static List<ConversationFocusHoldoutTest.WorkspaceInput> workspaces() {
        return List.of(new ConversationFocusHoldoutTest.WorkspaceInput(
                "workspace", "00000000-0000-0000-0000-000000000010",
                "owner", "PERSONAL"));
    }

    private static ConversationFocusHoldoutTest.ContextInput context(
            String key, String actor, String workspace, String channel) {
        return new ConversationFocusHoldoutTest.ContextInput(
                key, actor, workspace, channel, "holdout", key + "-scope");
    }

    private static ConversationFocusHoldoutTest.MessageArtifactInput message() {
        return message("owner");
    }

    private static ConversationFocusHoldoutTest.MessageArtifactInput message(
            String contextKey) {
        return new ConversationFocusHoldoutTest.MessageArtifactInput(
                "quoted-message", contextKey, "OUT", "TEXT", "sealed quoted fact",
                "line-message-1", null);
    }

    private static ConversationFocusHoldoutTest.TurnInput intentTurn(
            String id, String context, String text, String quotedMessage,
            String replayOf, String saveAlias) {
        return new ConversationFocusHoldoutTest.TurnInput(
                id, "INTENT", context, text,
                "00000000-0000-0000-0000-0000000000" + ("create".equals(id) ? "21" : "22"),
                command(text), quotedMessage, replayOf, saveAlias, null);
    }

    private static ConversationFocusHoldoutTest.TurnInput quotedFocusTurn(
            String id, String context, String alias) {
        return new ConversationFocusHoldoutTest.TurnInput(
                id, "QUOTED_FOCUS", context, null,
                "00000000-0000-0000-0000-000000000023",
                null, null, null, null, alias);
    }

    private static ConversationFocusHoldoutTest.TurnInput replayTurn(
            String id, String context, String replayOf) {
        return new ConversationFocusHoldoutTest.TurnInput(
                id, "INTENT", context, null, null, null, null, replayOf, null, null);
    }

    private static IntentCommand command(String text) {
        return new IntentCommand(IntentCommand.Type.LIST_TASKS, null, null, null,
                null, null, null, null, null, null, null, null, null,
                IntentOptions.empty(), text);
    }
}
