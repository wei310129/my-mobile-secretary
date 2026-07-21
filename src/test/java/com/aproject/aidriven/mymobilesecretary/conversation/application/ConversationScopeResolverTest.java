package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.integration.line.LineWebhookPayload.Source;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationScopeResolverTest {

    @Test
    void resolvesPreviousVersionForRotationAndRestFallsBackToCanonicalScope() {
        ConversationScopeResolver resolver = new ConversationScopeResolver(
                new ConversationScopeProperties(2, "bmV3LWNvbnZlcnNhdGlvbi1zY29wZS1rZXk=", 1,
                        "b2xkLWNvbnZlcnNhdGlvbi1zY29wZS1rZXk="));
        WorkspaceContext rest = new WorkspaceContext(UUID.randomUUID(), UUID.randomUUID(),
                WorkspaceChannel.REST);

        assertThat(resolver.current(rest).keyVersion()).isEqualTo(2);
        assertThat(resolver.previous(rest)).hasValueSatisfying(previous ->
                assertThat(previous.keyVersion()).isEqualTo(1));
        assertThat(rest.conversationAdapterNamespace()).isEqualTo("legacy");
        assertThat(rest.conversationScopeToken()).isEqualTo("legacy-default");
    }

    @Test
    void lineTrustedSourceUsesGroupThenRoomThenUserWithoutPersistingItsRawValue() {
        assertThat(new Source("group", "user-a", "group-a", "room-a")
                .trustedConversationScopeToken()).isEqualTo("group:group-a");
        assertThat(new Source("room", "user-a", null, "room-a").trustedConversationScopeToken())
                .isEqualTo("room:room-a");
        assertThat(new Source("user", "user-a", null, null).trustedConversationScopeToken())
                .isEqualTo("user:user-a");
    }

    @Test
    void incompleteRotationConfigurationFailsClosed() {
        assertThatThrownBy(() -> new ConversationScopeProperties(2,
                "bmV3LWNvbnZlcnNhdGlvbi1zY29wZS1rZXk=", 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configured together");
    }
}
