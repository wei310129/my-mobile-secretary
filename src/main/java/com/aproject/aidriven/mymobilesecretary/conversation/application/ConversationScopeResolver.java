package com.aproject.aidriven.mymobilesecretary.conversation.application;

import com.aproject.aidriven.mymobilesecretary.account.workspace.WorkspaceContext;
import com.aproject.aidriven.mymobilesecretary.conversation.domain.ConversationScopeKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Resolves trusted adapter scope inputs without persisting their raw tokens. */
@Component
public class ConversationScopeResolver {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final ConversationScopeProperties properties;

    public ConversationScopeResolver(ConversationScopeProperties properties) {
        this.properties = properties;
    }

    public ConversationScopeKey current(WorkspaceContext context) {
        return resolve(context, properties.currentKeyVersion(), properties.currentHmacKeyBase64());
    }

    public Optional<ConversationScopeKey> previous(WorkspaceContext context) {
        if (properties.previousKeyVersion() == null) {
            return Optional.empty();
        }
        return Optional.of(resolve(context, properties.previousKeyVersion(),
                properties.previousHmacKeyBase64()));
    }

    private static ConversationScopeKey resolve(WorkspaceContext context, int keyVersion,
                                                String keyBase64) {
        String material = String.join("\u001f", context.workspaceId().toString(),
                context.actorId().toString(), context.conversationAdapterNamespace(),
                context.channel().name(), context.conversationScopeToken());
        return new ConversationScopeKey(hmacHex(material, keyBase64), keyVersion);
    }

    private static String hmacHex(String material, String keyBase64) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(keyBase64), HMAC_ALGORITHM));
            byte[] bytes = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (IllegalArgumentException | GeneralSecurityException failure) {
            throw new IllegalStateException("conversation scope key is unavailable", failure);
        }
    }
}
