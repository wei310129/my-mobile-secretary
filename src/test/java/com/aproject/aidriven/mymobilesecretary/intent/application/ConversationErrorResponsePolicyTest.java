package com.aproject.aidriven.mymobilesecretary.intent.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationErrorResponsePolicyTest {

    private final ConversationErrorResponsePolicy policy = new ConversationErrorResponsePolicy();

    @Test
    void roleDenialUsesOneFixedPublicReply() {
        PublicConversationReply reply = policy.publicReply(new ConversationDiagnostic(
                ConversationDiagnostic.Code.WORKSPACE_ROLE_DENIED,
                "handler=PrivateRoleHandler workspaceId=private-value"));

        assertThat(reply.message())
                .contains("可以查看", "還不能透過對話修改內容")
                .doesNotContain("PrivateRoleHandler", "workspaceId", "private-value");
        assertThat(reply.terminalState()).isEqualTo(PublicConversationReply.TerminalState.FAILED);
    }

    @Test
    void businessRuleFailuresUseAllowlistedPublicReplies() {
        for (String code : new String[] {"MISSING_COORDINATES", "PLACE_NOT_FOUND_ON_GOOGLE"}) {
            PublicConversationReply reply = policy.publicReply(new ConversationDiagnostic(
                    ConversationDiagnostic.Code.BUSINESS_RULE_REJECTED, code));

            assertThat(reply.message())
                    .containsAnyOf("可以提供地址", "提供更完整")
                    .doesNotContain("provider", "exception", "handler", "schema");
            assertThat(reply.terminalState())
                    .isEqualTo(PublicConversationReply.TerminalState.NEEDS_INPUT);
            assertThat(reply.nextQuestion()).isNotNull();
            assertThat(reply.message().chars().filter(value -> value == '？').count()).isEqualTo(1);
        }
    }

    @Test
    void transientLookupFailureDoesNotPretendThatAQuestionIsPending() {
        PublicConversationReply reply = policy.publicReply(new ConversationDiagnostic(
                ConversationDiagnostic.Code.BUSINESS_RULE_REJECTED, "PLACE_LOOKUP_FAILED"));

        assertThat(reply.terminalState()).isEqualTo(PublicConversationReply.TerminalState.FAILED);
        assertThat(reply.nextQuestion()).isNull();
        assertThat(reply.message()).contains("暫時查不到", "資料沒有異動");
    }
}
