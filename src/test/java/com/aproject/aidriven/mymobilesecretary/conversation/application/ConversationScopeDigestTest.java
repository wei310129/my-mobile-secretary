package com.aproject.aidriven.mymobilesecretary.conversation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceChannel;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationScopeDigestTest {

    private static final String TEST_KEY = "dGVzdC1jb252ZXJzYXRpb24tc2NvcGUtaG1hYy1rZXk=";
    private static final UUID ACTOR = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID WORKSPACE = UUID.fromString("20000000-0000-0000-0000-000000000101");

    @Test
    void isStableAcrossResolverRestartAndDoesNotExposeRawToken() {
        WorkspaceContext context = context(WorkspaceChannel.LINE, "line", "group:private-room-42");

        ConversationScopeKey first = resolver().current(context);
        ConversationScopeKey restarted = resolver().current(context);

        assertThat(first).isEqualTo(restarted);
        assertThat(first.digest()).doesNotContain("private-room-42", "line", ACTOR.toString(),
                WORKSPACE.toString(), TEST_KEY);
    }

    @Test
    void separatesAdapterChannelAndTrustedTokens() {
        ConversationScopeResolver resolver = resolver();

        assertThat(resolver.current(context(WorkspaceChannel.LINE, "line", "group:a")).digest())
                .isNotEqualTo(resolver.current(context(WorkspaceChannel.LINE, "line", "group:b")).digest())
                .isNotEqualTo(resolver.current(context(WorkspaceChannel.REST, "rest", "legacy-default"))
                        .digest());
    }

    private static ConversationScopeResolver resolver() {
        return new ConversationScopeResolver(new ConversationScopeProperties(1, TEST_KEY, null, null));
    }

    private static WorkspaceContext context(WorkspaceChannel channel, String adapter, String token) {
        return new WorkspaceContext(ACTOR, WORKSPACE, channel, adapter, token);
    }
}
