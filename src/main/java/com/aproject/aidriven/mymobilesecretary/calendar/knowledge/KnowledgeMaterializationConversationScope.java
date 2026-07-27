package com.aproject.aidriven.mymobilesecretary.calendar.knowledge;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContextHolder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Server-derived scope shared by proposal capture and confirmation. */
public record KnowledgeMaterializationConversationScope(
        String channel, String scopeKey) {

    public static KnowledgeMaterializationConversationScope current() {
        WorkspaceContext context = WorkspaceContextHolder.requireContext();
        if (!context.isTenantScope()) {
            throw new SecurityException(
                    "Knowledge materialization requires tenant scope");
        }
        return new KnowledgeMaterializationConversationScope(
                context.channel().name(),
                "intent:" + hash(
                        context.conversationAdapterNamespace()
                                + "|" + context.conversationScopeToken()));
    }

    private static String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
